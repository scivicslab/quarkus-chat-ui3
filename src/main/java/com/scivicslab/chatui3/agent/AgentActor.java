package com.scivicslab.chatui3.agent;

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
 * The brain of the in-process ReAct agent loop (one instance per browser turn).
 *
 * <p>Driven by {@code agent-react.yaml}: {@code start} → ({@code stepExpectingAction} → {@code runTool})*
 * → {@code finish}. Each {@code stepExpectingAction} calls the LLM once with
 * {@code [ReAct system] + history + question + scratchpad} and parses the ReAct output:
 * an {@code Action} succeeds (run the tool, loop), a {@code Final Answer} fails the transition
 * (fall through to {@code finish}).</p>
 *
 * <p>Only the final answer is committed to {@link ConversationStore}; the Thought/Action/Observation
 * scratchpad stays within the turn. {@link #cancel()} aborts the in-flight LLM call and ends quietly.</p>
 */
public class AgentActor extends IIActorRef<Object> {

    private static final Logger LOG = Logger.getLogger(AgentActor.class.getName());
    private static final int MAX_STEPS = 6;
    /** Safety margin (tokens) reserved on top of max_tokens when budgeting the request. */
    private static final int OUTPUT_MARGIN = 256;

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant that can use tools to answer questions.\n"
          + "You have access to the following tools:\n"
          + "- calc: evaluate a Java expression and return its value. Input: a Java expression,\n"
          + "  e.g. 23*47 or Math.sqrt(16) or (1000.0/3).\n"
          + "- read: read a file, or recursively a directory, under the working directory and return\n"
          + "  its text. Input: a relative path, e.g. docs/notes.md or docs/standards\n\n"
          + "Use this exact format:\n"
          + "Thought: <your reasoning>\n"
          + "Action: <tool name>\n"
          + "Action Input: <input>\n"
          + "Observation: <the tool result will be given to you>\n"
          + "... (Thought/Action/Action Input/Observation may repeat) ...\n"
          + "Thought: I now know the final answer.\n"
          + "Final Answer: <your answer to the user>\n\n"
          + "Rules:\n"
          + "- Always use the calc tool for arithmetic instead of computing it yourself.\n"
          + "- After \"Action Input:\" stop; do NOT write the Observation yourself, it will be provided.\n"
          + "- You MUST finish with a line that starts exactly with \"Final Answer:\".\n\n"
          + "Example:\n"
          + "Question: What is 23 * 47?\n"
          + "Thought: I should use the calculator.\n"
          + "Action: calc\n"
          + "Action Input: 23*47\n"
          + "Observation: 1081\n"
          + "Thought: I now know the final answer.\n"
          + "Final Answer: 23 * 47 = 1081\n\n"
          + "Begin!";

    private final VllmClient vllmClient;
    private final ChatUiConfig config;
    private final ActorRef<SseActor> sseRef;
    private final ConversationStore conversation;
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

    // per-turn working memory
    private String question;
    private int turnNo;   // conversation turn number, for labelling I/O log entries
    private final List<Map<String, Object>> scratchpad = new ArrayList<>();
    private String pendingTool;
    private String pendingInput;
    private String finalAnswer;
    private int stepCount;

    private volatile Thread worker;
    private volatile boolean cancelled;

    public AgentActor(String name, VllmClient vllmClient, ChatUiConfig config,
            ActorRef<SseActor> sseRef, ConversationStore conversation, IIActorSystem system,
            IoLogStore ioLog, long sessionId, int contextWindow) {
        super(name, new Object(), system);
        this.vllmClient    = vllmClient;
        this.config        = config;
        this.sseRef        = sseRef;
        this.conversation  = conversation;
        this.ioLog         = ioLog;
        this.sessionId     = sessionId;
        this.contextWindow = contextWindow;
    }

    /** Initialises the turn from the user's message. */
    @Action("start")
    public ActionResult start(String args) {
        this.question = parseFirstArgument(args);
        // Number this turn within the conversation (committed history holds user+assistant pairs).
        this.turnNo = conversation.historyTurns().size() / 2 + 1;
        this.scratchpad.clear();
        this.pendingTool = null;
        this.pendingInput = null;
        this.finalAnswer = null;
        this.stepCount = 0;
        this.cancelled = false;
        return new ActionResult(true, "started");
    }

    /**
     * One LLM step. Succeeds (→ run the tool) if the model asked for an action; fails
     * (→ finish) if it produced a final answer, hit the step limit, or was cancelled.
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
            // Stop at the tool boundary: without this the model fabricates its own "Observation:"
            // and a full answer in one shot, defeating the loop. We inject the real Observation.
            // Stream the reasoning live to the browser's per-turn "thinking" block (kept separate
            // from the final answer); the real Observation is streamed into the same block by runTool.
            // thinkingStep() marks this step's start so the UI can drop it if it becomes the final
            // answer (otherwise the same text would appear in both the thinking block and the bubble).
            sseRef.tell(a -> a.emit(ChatEvent.thinkingStep()));
            // The LLM call is recorded at the shared VllmClient point via this LogContext.
            VllmClient.LogContext logCtx =
                    new VllmClient.LogContext(sessionId, "agent", "turn" + turnNo + "/step" + stepCount + "/llm");
            vllmClient.streamCompletion(messages, config, List.of("Observation:"), logCtx,
                    fragment -> sseRef.tell(a -> a.emit(ChatEvent.thinking(fragment))),
                    resp -> respHolder[0] = resp);
            VllmResponse resp = respHolder[0];
            String text = resp != null ? resp.fullText() : "";

            String finalAns = afterMarker(text, "Final Answer:");
            if (finalAns != null) {
                this.finalAnswer = finalAns;
                // This step is the answer, not an intermediate tool step: remove it from the trace.
                sseRef.tell(a -> a.emit(ChatEvent.thinkingDrop()));
                return new ActionResult(false, "final");
            }

            String tool = lineAfterMarker(text, "Action:");
            String input = afterMarker(text, "Action Input:");
            if (tool != null && input != null && !tool.isBlank()) {
                this.pendingTool = tool.trim();
                this.pendingInput = firstLine(input).trim();
                this.scratchpad.add(message("assistant", text));   // record the model's Thought/Action
                return new ActionResult(true, "action");
            }

            // Unparseable: treat the whole text as the final answer (avoid looping forever).
            this.finalAnswer = text.trim();
            sseRef.tell(a -> a.emit(ChatEvent.thinkingDrop()));
            return new ActionResult(false, "final (unparsed)");
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

    /** Executes the pending tool, appends the observation to the scratchpad, loops back to think. */
    @Action("runTool")
    public ActionResult runTool(String args) {
        String observation;
        try {
            observation = runToolImpl(pendingTool, pendingInput);
        } catch (Exception e) {
            observation = "error: " + e.getMessage();
        }
        // Budget (s_budget): a huge observation (e.g. a file read) is truncated head+tail for the
        // copy the model and the thinking block see; the FULL observation still goes to the I/O log.
        final String fullObs = observation;
        final String forModel = ContextBudget.truncateObservation(fullObs);
        this.scratchpad.add(message("user", "Observation: " + forModel));
        // The real tool result is injected by us (not generated), so stream it into the thinking
        // block so the on-screen trace matches what actually fed back into the loop.
        sseRef.tell(a -> a.emit(ChatEvent.thinking("Observation: " + forModel + "\n")));
        // Persist the complete I/O of this tool call (input + FULL output, untruncated).
        if (ioLog != null) {
            ioLog.record(sessionId, "agent", "turn" + turnNo + "/step" + stepCount + "/tool",
                    "TOOL: " + pendingTool + "\nINPUT:\n" + pendingInput + "\nOBSERVATION:\n" + fullObs);
        }
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
        conversation.commitTurn(question, answer);
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

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Returns the text after the first occurrence of marker, or null if absent. */
    private static String afterMarker(String text, String marker) {
        if (text == null) return null;
        int i = text.indexOf(marker);
        if (i < 0) return null;
        return text.substring(i + marker.length()).trim();
    }

    /** Returns the first line after the marker (e.g. the tool name on the Action: line). */
    private static String lineAfterMarker(String text, String marker) {
        String rest = afterMarker(text, marker);
        return rest == null ? null : firstLine(rest);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
