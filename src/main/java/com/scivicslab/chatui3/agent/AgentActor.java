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
            "You are a helpful assistant. You can call the provided tools to read project files and\n"
          + "to evaluate arithmetic. Prefer calling a tool over guessing: use 'read' to fetch the\n"
          + "actual contents of files or directories the user refers to, and 'calc' for any arithmetic.\n"
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
          + "  call is needed now (what you are trying to find or verify). Make it specific, not boilerplate.";

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

    // per-turn working memory
    private String question;
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
            IoLogStore ioLog, long sessionId, int contextWindow, ObjectMapper mapper, String source) {
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
        return new ActionResult(true, "started");
    }

    /**
     * One LLM step. Succeeds (→ run the tools) if the model asked to call tools; fails (→ finish)
     * if it produced a final answer, hit the step limit, or was cancelled.
     */
    @Action("stepExpectingAction")
    public ActionResult stepExpectingAction(String args) {
        if (cancelled) {
            return new ActionResult(false, "cancelled");
        }
        if (++stepCount > MAX_STEPS) {
            this.finalAnswer = "(step limit reached)";
            return new ActionResult(false, "step limit");
        }
        this.worker = Thread.currentThread();
        try {
            List<Map<String, Object>> messages = buildMessages();
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
                // Record the assistant's tool-call turn so the follow-up request is well-formed.
                this.scratchpad.add(assistantToolCallMessage(resp.fullText(), pendingCalls));
                // Show the calls in the live trace (content is usually empty on a tool-call step).
                for (VllmResponse.ToolCall tc : pendingCalls) {
                    sseRef.tell(a -> a.emit(ChatEvent.thinking(
                            "\n→ " + tc.name() + "(" + tc.arguments() + ")\n")));
                }
                return new ActionResult(true, "action");
            }

            // No tool calls: the content is the final answer.
            String text = resp != null ? resp.fullText() : "";
            this.finalAnswer = text.trim();
            sseRef.tell(a -> a.emit(ChatEvent.thinkingDrop()));
            return new ActionResult(false, "final");
        } catch (Exception e) {
            if (cancelled) {
                return new ActionResult(false, "cancelled");
            }
            LOG.log(Level.WARNING, "agent step failed", e);
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
            String observation;
            try {
                observation = runToolImpl(tc.name(), toolInput);
            } catch (Exception e) {
                observation = "error: " + e.getMessage();
            }
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
            return new ActionResult(true, "ended");   // cancelled / errored: end quietly
        }
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
                 "Read a file, or recursively a directory, under the working directory and return its "
               + "text content. Use this to read the actual project files and documentation the user "
               + "refers to.",
                 "path",
                 "Relative path under the working directory, e.g. docs/notes.md or "
               + "doc_SCIVICS001/docs/CodingStandard/010_ProjectStandards"),
            tool("calc",
                 "Evaluate a Java arithmetic expression and return its value. Always use this for "
               + "arithmetic instead of computing it yourself.",
                 "expression",
                 "A Java expression, e.g. 23*47 or Math.sqrt(16) or (1000.0/3)"));

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
        Map<String, Object> system = message("system", SYSTEM_PROMPT);
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

    private String runToolImpl(String tool, String input) {
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
        return "error: unknown tool '" + tool + "'";
    }

    /**
     * Extracts the single string argument from the model's tool-call {@code arguments} JSON. Tries the
     * tool's declared parameter name first (path/expression), then falls back to the first string
     * value, so a model that names the field differently still works.
     */
    private String extractInput(String tool, String argumentsJson) {
        String preferred = "calc".equals(tool) ? "expression" : "path";
        try {
            JsonNode root = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            JsonNode pref = root.path(preferred);
            if (pref.isValueNode() && !pref.asText().isBlank()) {
                return pref.asText();
            }
            // Fallback: first string-valued field.
            var it = root.fields();
            while (it.hasNext()) {
                var e = it.next();
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
