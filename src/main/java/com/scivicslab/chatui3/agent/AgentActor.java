package com.scivicslab.chatui3.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.actor.SseActor;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.context.ContextBudget;
import com.scivicslab.chatui3.context.ConversationStore;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.llm.VllmClient;
import com.scivicslab.chatui3.llm.VllmResponse;
import com.scivicslab.chatui3.rest.ChatEvent;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.examples.jshell.JShellCalculator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The brain of the in-process agent loop (one instance per browser turn). Uses the model's NATIVE
 * function calling (OpenAI {@code tool_calls}): the tool schemas are sent with each request and the
 * model replies either with a final answer or with structured tool calls.
 *
 * <p>Driven by {@code agent-react.yaml}: {@code start} → ({@code stepExpectingAction} → {@code runTool})*
 * → {@code finish}. Each {@code stepExpectingAction} calls the LLM once with the tool schemas;
 * if it returns {@code tool_calls} the transition succeeds (run the tools, loop), otherwise the
 * content is the final answer and the transition fails (fall through to {@code finish}).</p>
 *
 * <p>Only the final answer is committed to {@link ConversationStore}; the tool-call/observation
 * scratchpad stays within the turn. {@link #cancel()} aborts the in-flight LLM call and ends quietly.</p>
 */
public class AgentActor extends IIActorRef<Object> {

    private static final Logger LOG = Logger.getLogger(AgentActor.class.getName());
    private static final int MAX_STEPS = 6;
    /** Safety margin (tokens) reserved on top of max_tokens when budgeting the request. */
    private static final int OUTPUT_MARGIN = 256;

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. You can call the provided tools instead of guessing: 'read'\n"
          + "to fetch the actual contents of files/directories the user refers to, 'calc' for any\n"
          + "arithmetic, 'web_search' for current events or external facts not in the working directory\n"
          + "(it searches AND retrieves the top result pages' actual content, not just snippets, so you do\n"
          + "not need a separate fetch step), 'fetch' to read one specific URL you already have (e.g. a URL\n"
          + "the user gave, or a JSON/data endpoint),\n"
          + "'search_docs' to look up the team's own internal documentation by meaning (use it first for\n"
          + "questions about this team's projects/systems/conventions), and 'write' to save text to a\n"
          + "file when the user asks to save/export something.\n"
          + "\n"
          + "You act ONLY within this single reply. You cannot do work after you answer, you cannot run\n"
          + "anything in the background, and you cannot 'report back later'. There is no later turn that\n"
          + "you control. Therefore:\n"
          + "- NEVER say you will do something afterwards (no 'I will start reading', 'I will report\n"
          + "  back when done', 'let me begin', etc.). Announcing a plan does nothing — only a tool call\n"
          + "  actually does anything.\n"
          + "- When the user names one or more files or directories to read, immediately call the 'read'\n"
          + "  tool for EVERY one of them (you may request several tool calls at once). Do this BEFORE\n"
          + "  writing any prose. Read happens inside this same reply via the tool, not 'later'.\n"
          + "- Only after the tool results come back, write your final answer in plain text in this same\n"
          + "  turn. If you have nothing left to read, just answer.\n"
          + "- Every tool call requires a 'reason' argument: state, in one concise sentence, why THIS\n"
          + "  call is needed now (what you are trying to find or verify). Make it specific, not boilerplate.\n"
          + "- 'read' reads DIRECTORIES too (recursively). To inspect a small folder or a specific file,\n"
          + "  call read on the path itself — do NOT claim you cannot access it and do NOT ask the user\n"
          + "  for individual file paths. Paths like ~/works/X, $HOME/works/X or an absolute path all\n"
          + "  resolve into the working directory; pass them as given.\n"
          + "- For two KNOWN, well-defined jobs, prefer 'run_known_task' over 'read', because it runs a\n"
          + "  reliable workflow that handles even large inputs that do not fit in one read:\n"
          + "    * Understanding/summarizing/documenting a WHOLE code project or repository\n"
          + "      -> run_known_task(task='understand_project', path=<project directory>). It scans the\n"
          + "      project, summarizes every file in parallel, and synthesizes one overview. Use this\n"
          + "      instead of read when the user points at a whole project/folder to understand.\n"
          + "    * Faithfully translating an entire document into Japanese\n"
          + "      -> run_known_task(task='translate_document', path=<document file>).\n"
          + "  The task result comes back as the observation; then present it as your final answer.\n"
          + "- USER-DEFINED WORKFLOWS: for domain-specific tasks (e.g. searching OpenAlex for papers,\n"
          + "  summarizing an arXiv paper, OCR), the workflows most relevant to THIS request are already\n"
          + "  listed for you at the end of this message under 'Available workflows for this request'\n"
          + "  (the harness pre-searched them from the user's message — you do NOT call any search tool).\n"
          + "  If one of them fits the request, call 'run_workflow' with its EXACT name and a JSON params\n"
          + "  string built from the params shown. Prefer a matching workflow over 'web_search': do NOT\n"
          + "  web-search a task a listed workflow already performs. If none fit, ignore the list.\n"
          + "- DOC-FIRST GATE: before you BUILD ON, EXTEND, or IMPLEMENT WITH a framework, library, or\n"
          + "  system — especially before adding a new capability to one — FIRST call 'search_docs' for\n"
          + "  that framework's existing capabilities and conventions (use a specific concept word in the\n"
          + "  query). Skim the returned titles/summaries, then call 'fetch' on the most relevant\n"
          + "  result's url to read the full document before writing code — do not guess a local file\n"
          + "  path. Do not reinvent something that already exists or violate an existing convention.\n"
          + "  This gate applies to engineering tasks, not casual questions.\n"
          + "- WEB SOURCES: whenever your answer draws on web_search, end the answer with a 'Sources'\n"
          + "  section. First list EVERY result you searched, one per line as '<n>. <title> — <URL>'.\n"
          + "  Then add a 'References (actually used)' line naming the subset of those URLs whose content\n"
          + "  you actually relied on for the answer, and attribute concrete facts to the specific URL\n"
          + "  they came from. (web_search returns the title and URL of every result, so you always have\n"
          + "  them.) Write the section in the user's language.";

    private final VllmClient vllmClient;
    private final ChatUiConfig config;
    private final ActorRef<SseActor> sseRef;
    private final ConversationStore conversation;
    private final ObjectMapper mapper;
    // Complete I/O log (s_iolog) and the conversation's session id. LLM calls are logged at the shared
    // VllmClient point (via a LogContext); tool calls are logged here directly. node id is "agent".
    private final IoLogStore ioLog;
    private final long sessionId;
    // Effective context-window limit (tokens) used to budget the request (s_budget).
    private final int contextWindow;
    // The "calc" tool delegates to Turing Workflow's JShell-backed evaluator (reused, not reinvented):
    // it evaluates Java expressions (23*47, Math.sqrt(16), ...). Created lazily on first use and kept
    // for the whole turn so JShell.create() is paid at most once, then closed with the actor.
    private JShellCalculator calc;

    // Who entered this turn's prompt ("browser" = the UI user, else e.g. "api"). Recorded with the
    // committed turn so the chat view can show prompts entered by non-UI clients.
    private final String source;

    // Dispatches known well-defined tasks (understand a project / translate a document) to a
    // deterministic Turing Workflow. Backs the 'run_known_task' tool.
    private final WorkflowDispatcher dispatcher;

    // Lucene in-memory index over ~/works/workflow/*.yaml. The HARNESS (not the model) searches it
    // once per turn with the user's message and injects the top matches into the system prompt, so
    // tool discovery is deterministic rather than depending on the model choosing to call a search tool.
    private final WorkflowIndex workflowIndex;
    /** How many workflow matches the harness injects into the system prompt each turn. */
    private static final int WORKFLOW_CATALOG_SIZE = 5;

    // per-turn working memory
    private String question;
    // The top workflow matches for THIS turn's user message, formatted for the system prompt.
    private String workflowCatalog = "";
    private int turnNo;   // conversation turn number, for labelling I/O log entries
    // The in-turn scratchpad in OpenAI message form: assistant tool-call messages and the
    // role:"tool" results we feed back, accumulated across steps.
    private final List<Map<String, Object>> scratchpad = new ArrayList<>();
    private List<VllmResponse.ToolCall> pendingCalls;
    private String finalAnswer;
    private int stepCount;

    private volatile Thread worker;
    private volatile boolean cancelled;

    public AgentActor(String name, VllmClient vllmClient, ChatUiConfig config,
            ActorRef<SseActor> sseRef, ConversationStore conversation, IIActorSystem system,
            IoLogStore ioLog, long sessionId, int contextWindow, ObjectMapper mapper, String source,
            WorkflowDispatcher dispatcher, WorkflowIndex workflowIndex) {
        super(name, new Object(), system);
        this.vllmClient    = vllmClient;
        this.config        = config;
        this.sseRef        = sseRef;
        this.conversation  = conversation;
        this.ioLog         = ioLog;
        this.sessionId     = sessionId;
        this.contextWindow = contextWindow;
        this.mapper        = mapper;
        this.source        = source;
        this.dispatcher    = dispatcher;
        this.workflowIndex = workflowIndex;
    }

    /** Initialises the turn from the user's message. */
    @Action("start")
    public ActionResult start(String args) {
        this.question = parseFirstArgument(args);
        // Number this turn within the conversation (committed history holds user+assistant pairs).
        this.turnNo = conversation.historyTurns().size() / 2 + 1;
        this.scratchpad.clear();
        this.pendingCalls = null;
        this.finalAnswer = null;
        this.stepCount = 0;
        this.cancelled = false;
        // Harness-side tool discovery: search the workflow index with the user's message NOW and inject
        // the top matches into the system prompt. The model never has to decide to call a search tool.
        this.workflowCatalog = buildWorkflowCatalog(question);
        String qPreview = question == null ? "" :
                (question.length() > 100 ? question.substring(0, 100) + "…" : question);
        LOG.info("turn" + turnNo + ": START agent loop, source=" + source
                + ", vllm=" + config.getVllmBaseUrl() + ", user=" + qPreview
                + ", workflowCatalog=" + (workflowCatalog.isEmpty() ? "empty" : workflowCatalog.length() + " chars"));
        return new ActionResult(true, "started");
    }

    /**
     * Runs the workflow index against the user's message and formats the top matches as a system-prompt
     * block. Returns "" when there is no index or no message, so buildMessages() can append unconditionally.
     */
    private String buildWorkflowCatalog(String userMessage) {
        if (workflowIndex == null || userMessage == null || userMessage.isBlank()) {
            return "";
        }
        try {
            String hits = workflowIndex.search(userMessage, WORKFLOW_CATALOG_SIZE);
            if (hits == null || hits.isBlank() || hits.startsWith("error:")
                    || hits.startsWith("Workflow index is not available")) {
                return "";
            }
            return "\n\n--- Available workflows for this request (pre-searched by the harness; "
                 + "call run_workflow if one fits) ---\n" + hits;
        } catch (Exception e) {
            LOG.log(Level.FINE, "workflow catalog build failed", e);
            return "";
        }
    }

    /** Comma-joined tool names for a compact log line. */
    private static String toolCallNames(List<VllmResponse.ToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(calls.get(i).name());
        }
        return sb.toString();
    }

    /**
     * One LLM step. Succeeds (→ run the tools) if the model asked to call tools; fails (→ finish)
     * if it produced a final answer, hit the step limit, or was cancelled.
     */
    @Action("stepExpectingAction")
    public ActionResult stepExpectingAction(String args) {
        if (cancelled) {
            LOG.info("turn" + turnNo + ": step cancelled before start");
            return new ActionResult(false, "cancelled");
        }
        if (++stepCount > MAX_STEPS) {
            LOG.warning("turn" + turnNo + ": step limit reached (MAX_STEPS=" + MAX_STEPS
                    + "); ending turn without a model answer");
            this.finalAnswer = "(step limit reached)";
            return new ActionResult(false, "step limit");
        }
        this.worker = Thread.currentThread();
        try {
            List<Map<String, Object>> messages = buildMessages();
            LOG.info("turn" + turnNo + "/step" + stepCount + ": calling LLM ("
                    + messages.size() + " messages, " + scratchpad.size() + " scratchpad, "
                    + TOOLS.size() + " tools offered)");
            final VllmResponse[] respHolder = {null};
            // Stream reasoning/content live to the browser's per-turn "thinking" block (kept separate
            // from the final answer). thinkingStep() marks this step's start so the UI can drop it if
            // it becomes the final answer (otherwise the same text would show twice).
            sseRef.tell(a -> a.emit(ChatEvent.thinkingStep()));
            // The LLM call is recorded at the shared VllmClient point via this LogContext. Tool schemas
            // are sent so the model can answer with native tool_calls.
            VllmClient.LogContext logCtx =
                    new VllmClient.LogContext(sessionId, "agent", "turn" + turnNo + "/step" + stepCount + "/llm");
            vllmClient.streamCompletion(messages, config, null, TOOLS, logCtx,
                    fragment -> sseRef.tell(a -> a.emit(ChatEvent.thinking(fragment))),
                    resp -> respHolder[0] = resp);
            VllmResponse resp = respHolder[0];

            if (resp != null && resp.hasToolCalls()) {
                this.pendingCalls = resp.toolCalls();
                LOG.info("turn" + turnNo + "/step" + stepCount + ": model returned "
                        + pendingCalls.size() + " NATIVE tool call(s): " + toolCallNames(pendingCalls));
                // Record the assistant's tool-call turn so the follow-up request is well-formed.
                this.scratchpad.add(assistantToolCallMessage(resp.fullText(), pendingCalls));
                // Show the calls in the live trace (content is usually empty on a tool-call step).
                for (VllmResponse.ToolCall tc : pendingCalls) {
                    sseRef.tell(a -> a.emit(ChatEvent.thinking(
                            "\n→ " + tc.name() + "(" + tc.arguments() + ")\n")));
                }
                return new ActionResult(true, "action");
            }

            // No NATIVE tool calls. Fallback: some models (gemma-4 in multi-turn) emit the tool call as
            // plain-text XML (<function_calls><invoke .../>) instead of via the tool_calls channel; the
            // vLLM parser misses it, so without this the raw XML would be committed as the final answer.
            String text = resp != null ? resp.fullText() : "";
            List<VllmResponse.ToolCall> textCalls = TextToolCallParser.parse(text);
            if (!textCalls.isEmpty()) {
                LOG.info("turn" + turnNo + "/step" + stepCount + ": no native tool_calls, but RECOVERED "
                        + textCalls.size() + " text-emitted tool call(s) the model wrote as XML: "
                        + toolCallNames(textCalls) + " (content " + text.length() + " chars)");
                this.pendingCalls = textCalls;
                // Keep history well-formed: record the prose (block stripped) plus native-format tool_calls,
                // so the next request shows the model the correct shape rather than replaying the XML.
                String prose = TextToolCallParser.stripToolCallBlocks(text);
                this.scratchpad.add(assistantToolCallMessage(prose, textCalls));
                for (VllmResponse.ToolCall tc : textCalls) {
                    sseRef.tell(a -> a.emit(ChatEvent.thinking(
                            "\n→ " + tc.name() + "(" + tc.arguments() + ")\n")));
                }
                return new ActionResult(true, "action");
            }

            // No tool calls at all: the content is the final answer.
            this.finalAnswer = text.trim();
            LOG.info("turn" + turnNo + "/step" + stepCount + ": model produced a FINAL answer ("
                    + finalAnswer.length() + " chars); ending loop");
            sseRef.tell(a -> a.emit(ChatEvent.thinkingDrop()));
            return new ActionResult(false, "final");
        } catch (Exception e) {
            if (cancelled) {
                LOG.info("turn" + turnNo + "/step" + stepCount + ": step interrupted by cancel");
                return new ActionResult(false, "cancelled");
            }
            LOG.log(Level.WARNING, "turn" + turnNo + "/step" + stepCount + ": agent step failed: "
                    + VllmClient.describe(e), e);
            sseRef.tell(a -> a.emit(ChatEvent.error(e.getMessage())));
            this.finalAnswer = null;
            return new ActionResult(false, "error");
        } finally {
            this.worker = null;
        }
    }

    /** Executes all pending tool calls, appends their results to the scratchpad, loops back to think. */
    @Action("runTool")
    public ActionResult runTool(String args) {
        if (pendingCalls == null) {
            return new ActionResult(true, "observed");
        }
        for (VllmResponse.ToolCall tc : pendingCalls) {
            String toolInput = extractInput(tc.name(), tc.arguments());
            String inputPreview = toolInput.length() > 120 ? toolInput.substring(0, 120) + "…" : toolInput;
            LOG.info("turn" + turnNo + "/step" + stepCount + ": executing tool " + tc.name()
                    + "(" + inputPreview + ")");
            long t0 = System.nanoTime();
            String observation;
            try {
                observation = runToolImpl(tc.name(), toolInput, tc.arguments());
            } catch (Exception e) {
                observation = "error: " + e.getMessage();
                LOG.log(Level.WARNING, "turn" + turnNo + "/step" + stepCount + ": tool " + tc.name()
                        + " threw: " + VllmClient.describe(e), e);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info("turn" + turnNo + "/step" + stepCount + ": tool " + tc.name() + " -> "
                    + observation.length() + " chars in " + ms + "ms"
                    + (observation.startsWith("error:") ? " [ERROR]" : ""));
            // Budget (s_budget): a huge observation (e.g. a file read) is truncated head+tail for the
            // copy the model sees; the FULL observation still goes to the I/O log.
            final String fullObs = observation;
            final String forModel = ContextBudget.truncateObservation(fullObs);
            // Feed the result back as a native tool message, matched to the call by id.
            scratchpad.add(toolResultMessage(tc.id(), forModel));
            sseRef.tell(a -> a.emit(ChatEvent.thinking("Observation: " + forModel + "\n")));
            // Persist the complete I/O of this tool call (input + FULL output, untruncated).
            if (ioLog != null) {
                ioLog.record(sessionId, "agent", "turn" + turnNo + "/step" + stepCount + "/tool",
                        "TOOL: " + tc.name() + "\nINPUT:\n" + toolInput + "\nOBSERVATION:\n" + fullObs);
            }
        }
        this.pendingCalls = null;
        return new ActionResult(true, "observed");
    }

    /** Emits the final answer (if any) and commits the completed turn to the conversation history. */
    @Action("finish")
    public ActionResult finish(String args) {
        if (cancelled || finalAnswer == null) {
            LOG.info("turn" + turnNo + ": FINISH without committing (cancelled=" + cancelled
                    + ", finalAnswer=" + (finalAnswer == null ? "null" : "present") + ")");
            return new ActionResult(true, "ended");   // cancelled / errored: end quietly
        }
        LOG.info("turn" + turnNo + ": FINISH, committing answer (" + finalAnswer.length()
                + " chars) after " + stepCount + " step(s)");
        String answer = finalAnswer;
        sseRef.tell(a -> a.emit(ChatEvent.delta(answer)));
        sseRef.tell(a -> a.emit(ChatEvent.result(answer)));
        conversation.commitTurn(question, answer, source);
        return new ActionResult(true, "finished");
    }

    /** Aborts the in-flight LLM call and marks the loop cancelled. */
    public void cancel() {
        this.cancelled = true;
        vllmClient.cancel();
        Thread w = this.worker;
        if (w != null) {
            w.interrupt();
        }
    }

    @Override
    public void close() {
        cancel();
        if (calc != null) {
            calc.close();
            calc = null;
        }
        super.close();
    }

    // ── tool schemas (native function calling) ──────────────────────────────────

    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("read",
                 "Read a file OR a whole directory (recursively) under the working directory and return "
               + "its text. Directories ARE supported: pass a directory path to read every file under it "
               + "(e.g. to summarize a project or folder). Use this to read the actual files/projects the "
               + "user refers to; never tell the user you cannot access a path — just call read.",
                 "path",
                 "A path into the working directory (the user's ~/works). Accepts a relative path "
               + "(e.g. POJO-actor or docs/notes.md), or a ~/works/... , $HOME/works/... , or absolute "
               + "/home/.../works/... form — all resolve into the working directory. For a whole "
               + "directory/project, pass the directory itself."),
            tool("calc",
                 "Evaluate a Java arithmetic expression and return its value. Always use this for "
               + "arithmetic instead of computing it yourself.",
                 "expression",
                 "A Java expression, e.g. 23*47 or Math.sqrt(16) or (1000.0/3)"),
            tool("web_search",
                 "Search the web (DuckDuckGo) AND retrieve the top result pages' actual content (not just "
               + "snippets) — it fetches the pages for you. Returns, per result, the title, URL, and a "
               + "page-content excerpt. Use this for current events, external facts, or anything not in "
               + "the working directory; you do NOT need to call 'fetch' afterwards. Then cite the results.",
                 "query",
                 "The search query, e.g. 'Quarkus 3.28 release notes' or 'vLLM tool calling gemma'."),
            tool("fetch",
                 "Fetch ONE specific URL you already have and return its readable page content as text — "
               + "e.g. a URL the user gave you, a link from an earlier result, or a JSON/data API endpoint. "
               + "(For open-ended web lookups use 'web_search', which already fetches the top pages.) Note: "
               + "pages rendered by JavaScript return only their static skeleton — fetch a JSON/data endpoint instead.",
                 "url",
                 "The absolute URL to fetch, e.g. https://quarkus.io/ or a JSON API URL."),
            tool("search_docs",
                 "Search the INTERNAL documentation (the team's own docs) by meaning and return matching "
               + "document titles, a fetchable 'url:', and summaries. Use this first for questions about "
               + "this team's projects, systems, conventions, or how-tos before searching the web. The "
               + "summaries are short; to read a document's full text, call 'fetch' with its url (do not "
               + "guess a local file path). Then cite the docs.",
                 "query",
                 "A natural-language query about the internal docs. Include the SPECIFIC concept word, e.g. "
               + "'Turing Workflow サブワークフロー 呼び出し' or 'POJO-actor tell ask の使い方', not just generic terms."),
            writeTool(),
            knownTaskTool(),
            // No 'search_tools' tool: the harness pre-searches the workflow index each turn and injects
            // the matches into the system prompt (see buildWorkflowCatalog), so the model calls
            // run_workflow directly without a model-driven discovery step.
            runWorkflowTool());

    /**
     * Builds the schema for {@code write}: save text to a file under the working directory. Two
     * parameters beyond {@code reason}: {@code path} (where to save) and {@code content} (what to save).
     */
    private static Map<String, Object> writeTool() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Why you are saving this file now. One concise sentence.");
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "Where to save, under the working directory (the user's ~/works). A "
                + "relative path (e.g. notes/summary.md) or ~/works/..., $HOME/works/... or an absolute "
                + "/home/.../works/... form. Parent directories are created. An existing directory is rejected.");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "The full text content to write to the file.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reason", reason);
        props.put("path", path);
        props.put("content", content);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("reason", "path", "content"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "write");
        fn.put("description", "Save text to a file under the working directory. Use when the user asks "
                + "to save, write, or export content to a file. Confined to the working directory.");
        fn.put("parameters", params);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    /**
     * Builds the schema for {@code run_known_task}: the single dispatcher tool that routes a known,
     * well-defined request to a deterministic Turing Workflow. Two parameters beyond {@code reason}:
     * {@code task} (an enum selecting the workflow) and {@code path} (the target). Keeping it to one
     * tool with a {@code task} enum caps the agent loop's tool count no matter how many workflows exist.
     */
    private static Map<String, Object> knownTaskTool() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Why you are dispatching this task now. One concise sentence.");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("enum", List.of("understand_project", "translate_document"));
        task.put("description", "understand_project: scan a code project and synthesize an overview "
                + "document (handles large projects via map-reduce). translate_document: faithfully "
                + "translate a whole document into Japanese.");
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "The target: a project directory for understand_project, or a document "
                + "file for translate_document. Accepts ~/works/..., $HOME/works/... or an absolute path.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reason", reason);
        props.put("task", task);
        props.put("path", path);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("reason", "task", "path"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "run_known_task");
        fn.put("description", "Run a reliable workflow for a KNOWN, well-defined job instead of reading "
                + "files yourself. Use for understanding/documenting a whole code project, or faithfully "
                + "translating a whole document. Returns the generated document as the observation.");
        fn.put("parameters", params);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    /**
     * Builds the schema for {@code run_workflow}: runs a user-defined workflow found via
     * {@code search_tools}. Two parameters beyond {@code reason}: {@code workflow} (the name returned
     * by search_tools) and {@code params} (a JSON-encoded object of workflow-specific key-value pairs).
     */
    private static Map<String, Object> runWorkflowTool() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Why you are running this workflow now. One concise sentence.");
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("type", "string");
        workflow.put("description", "Workflow name exactly as returned by search_tools (without .yaml extension).");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "string");
        params.put("description", "JSON object string of workflow-specific parameters as shown by search_tools, "
                + "e.g. \"{\\\"arxiv.id\\\": \\\"2511.08544\\\"}\".");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reason",   reason);
        props.put("workflow", workflow);
        props.put("params",   params);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", props);
        parameters.put("required", List.of("reason", "workflow", "params"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "run_workflow");
        fn.put("description", "Run a user-defined workflow found via search_tools. Returns the workflow "
                + "result as text for use as the observation. Always call search_tools first to get the "
                + "correct workflow name and required parameters.");
        fn.put("parameters", parameters);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    /**
     * Builds one OpenAI tool schema with a required {@code reason} parameter (why this call is being
     * made now) followed by the tool's operative string parameter. {@code reason} is listed first and
     * required so the model verbalizes its justification with every call — captured in the I/O log and
     * surfaced in the trace.
     */
    private static Map<String, Object> tool(String name, String description,
            String paramName, String paramDesc) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Why you are calling this tool now, given the user's request and what "
                + "you have read so far. One concise sentence.");
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("type", "string");
        param.put("description", paramDesc);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reason", reason);   // first, so the model states its reason before the argument
        props.put(paramName, param);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("reason", paramName));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", params);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildMessages() {
        // Prepend the current date/time so the model does not guess "today" (it has no clock and
        // would otherwise fabricate dates in web_search queries — e.g. searching last week's weather).
        // Append the harness-pre-searched workflow catalog so the model can call run_workflow directly
        // without a discovery round-trip.
        Map<String, Object> system =
                message("system", currentDatePreamble() + SYSTEM_PROMPT + workflowCatalog);
        Map<String, Object> user = message("user", question);
        // Budget the cross-turn history (s_budget): keep system + current user + this turn's
        // scratchpad, and fit as many recent history pairs as the token budget allows. Trimming
        // affects only what the model receives; the full history is preserved in the I/O log.
        int reserve = config.getMaxTokens() + OUTPUT_MARGIN;
        int fixed = ContextBudget.estimateTokens(List.of(system, user))
                + ContextBudget.estimateTokens(scratchpad);
        int historyBudget = contextWindow - reserve - fixed;
        List<Map<String, Object>> history =
                ContextBudget.fitHistory(conversation.historyTurns(), historyBudget);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(system);
        messages.addAll(history);
        messages.add(user);
        messages.addAll(scratchpad);
        return messages;
    }

    /**
     * A one-line preamble stating the current local date and time, so the model answers time-relative
     * questions ("this weekend", "today") against the real clock and builds correct web_search queries.
     */
    private static String currentDatePreamble() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        String date = now.format(java.time.format.DateTimeFormatter.ofPattern(
                "EEEE, yyyy-MM-dd HH:mm z", java.util.Locale.ENGLISH));
        return "The current date and time is " + date + ". Use this as \"now\" for any time-relative "
             + "question (today, this weekend, tomorrow) and in date-specific search queries.\n\n";
    }

    private String runToolImpl(String tool, String input, String rawArgs) {
        if ("calc".equals(tool)) {
            if (calc == null) {
                calc = new JShellCalculator();
            }
            return calc.evaluate(input);
        }
        if ("read".equals(tool)) {
            // Confined to the process working directory and below.
            return FileReadTool.read(java.nio.file.Path.of("").toAbsolutePath(), input);
        }
        if ("web_search".equals(tool)) {
            // Deterministic: search AND fetch the top results' page content in one step, so the model
            // never has to decide to call 'fetch' afterwards.
            return WebSearchTool.searchAndFetch(input);
        }
        if ("fetch".equals(tool)) {
            return FetchTool.fetch(input);
        }
        if ("search_docs".equals(tool)) {
            return DocSearchTool.search(input, DocSearchTool.DEFAULT_MAX_RESULTS);
        }
        if ("write".equals(tool)) {
            // Needs two fields (path + content); parse the raw tool arguments.
            String path = "";
            String content = "";
            try {
                JsonNode root = mapper.readTree(rawArgs == null ? "{}" : rawArgs);
                path = root.path("path").asText("");
                content = root.path("content").asText("");
            } catch (Exception e) {
                return "error: could not parse write arguments: " + e.getMessage();
            }
            return FileWriteTool.write(java.nio.file.Path.of("").toAbsolutePath(), path, content);
        }
        if ("run_workflow".equals(tool)) {
            // Needs two fields (workflow + params JSON), so parse raw args.
            String workflowName = "";
            Map<String, String> workflowParams = new LinkedHashMap<>();
            try {
                JsonNode root = mapper.readTree(rawArgs == null ? "{}" : rawArgs);
                workflowName = root.path("workflow").asText("");
                String paramsJson = root.path("params").asText("{}");
                JsonNode paramsNode = mapper.readTree(paramsJson.isBlank() ? "{}" : paramsJson);
                paramsNode.fields().forEachRemaining(e ->
                        workflowParams.put(e.getKey(), e.getValue().asText("")));
            } catch (Exception e) {
                return "error: could not parse run_workflow arguments: " + e.getMessage();
            }
            return dispatcher.runExternal(workflowName, workflowParams);
        }
        if ("run_known_task".equals(tool)) {
            // Dispatch a known job to a Turing Workflow. Needs two fields (task + path), so parse the
            // raw tool arguments rather than the single extracted input.
            String task = "";
            String path = "";
            try {
                JsonNode root = mapper.readTree(rawArgs == null ? "{}" : rawArgs);
                task = root.path("task").asText("");
                path = root.path("path").asText("");
            } catch (Exception e) {
                return "error: could not parse run_known_task arguments: " + e.getMessage();
            }
            return dispatcher.run(task, path);
        }
        return "error: unknown tool '" + tool + "'";
    }

    /**
     * Extracts the single operative string argument from the model's tool-call {@code arguments} JSON.
     * Each single-arg tool carries a {@code reason} plus one operative field; this resolves that field
     * by name per tool, then falls back to the first string field that is NOT {@code reason}. Never
     * returning {@code reason} is essential: the schema lists it first, so a plain "first string field"
     * fallback would feed the reason text to the tool whenever the model emits reason first.
     */
    private String extractInput(String tool, String argumentsJson) {
        String preferred = switch (tool) {
            case "calc" -> "expression";
            case "web_search", "search_docs" -> "query";
            case "fetch" -> "url";
            case "run_workflow" -> "workflow";
            default -> "path";   // read, and any single-path tool
        };
        try {
            JsonNode root = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            JsonNode pref = root.path(preferred);
            if (pref.isValueNode() && !pref.asText().isBlank()) {
                return pref.asText();
            }
            // Fallback: first string-valued field other than "reason".
            var it = root.fields();
            while (it.hasNext()) {
                var e = it.next();
                if ("reason".equals(e.getKey())) continue;
                if (e.getValue().isValueNode() && !e.getValue().asText().isBlank()) {
                    return e.getValue().asText();
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to parse tool arguments: " + argumentsJson, e);
        }
        return argumentsJson == null ? "" : argumentsJson;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Builds the assistant message carrying native tool_calls, for the follow-up request. */
    private static Map<String, Object> assistantToolCallMessage(
            String content, List<VllmResponse.ToolCall> calls) {
        List<Map<String, Object>> tcs = new ArrayList<>();
        for (VllmResponse.ToolCall tc : calls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.name());
            fn.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", tc.id());
            one.put("type", "function");
            one.put("function", fn);
            tcs.add(one);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content == null ? "" : content);
        m.put("tool_calls", tcs);
        return m;
    }

    /** Builds a native tool-result message, matched to its call by id. */
    private static Map<String, Object> toolResultMessage(String toolCallId, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }
}
