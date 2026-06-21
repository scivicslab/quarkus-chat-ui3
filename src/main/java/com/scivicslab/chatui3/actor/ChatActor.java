package com.scivicslab.chatui3.actor;

import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.llm.VllmClient;
import com.scivicslab.chatui3.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns session state: message history and ChatUiConfig.
 * Delegates LLM I/O to VllmClient on a virtual thread so the actor mailbox stays unblocked.
 */
public class ChatActor {

    private static final Logger LOG = Logger.getLogger(ChatActor.class.getName());

    private final VllmClient vllmClient;
    private final IoLogStore ioLog;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private int turnCount = 0;
    private long sessionId = -1;   // entry-A I/O-log session (opened lazily on first turn)
    private ChatUiConfig config;
    private ActorRef<SseActor> sseRef;

    public ChatActor(VllmClient vllmClient, ChatUiConfig config, IoLogStore ioLog) {
        this.vllmClient = vllmClient;
        this.config     = config;
        this.ioLog      = ioLog;
    }

    public void setSseRef(ActorRef<SseActor> sseRef) {
        this.sseRef = sseRef;
    }

    /**
     * Starts one LLM turn with the given user message.
     * Runs VllmClient on a virtual thread so the actor mailbox stays responsive.
     */
    public void startTurn(String userMessage) {
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        int turnNumber  = ++turnCount;
        // Open an entry-A I/O-log session on the first turn, so headless (Turing Workflow) I/O is
        // logged too — into its own session (workflow_name "chatui3-workflow"), node "agent".
        if (ioLog != null && sessionId < 0) {
            sessionId = ioLog.startNamedSession("chatui3-workflow");
        }
        // The LLM call is recorded at the shared VllmClient point via this context.
        VllmClient.LogContext logCtx =
                new VllmClient.LogContext(sessionId, "agent", "turn" + turnNumber + "/llm");

        // Copy messages snapshot for the virtual thread (messages list must not be modified mid-flight)
        List<Map<String, Object>> snapshot = List.copyOf(messages);
        ChatUiConfig configSnapshot        = config;

        Thread.ofVirtual().start(() -> {
            try {
                vllmClient.streamCompletion(
                    snapshot,
                    configSnapshot,
                    null,      // no stop sequence (single-shot)
                    logCtx,
                    // onDelta: push each fragment to SSE immediately
                    fragment -> {
                        if (sseRef != null) sseRef.tell(a -> a.emit(ChatEvent.delta(fragment)));
                    },
                    // onComplete: update history, signal result
                    response -> {
                        // Update history on the actor thread to avoid races
                        tell_self(a -> {
                            Map<String, Object> assistantMsg = new LinkedHashMap<>();
                            assistantMsg.put("role", "assistant");
                            assistantMsg.put("content", response.fullText());
                            a.messages.add(assistantMsg);
                        });

                        if (sseRef != null) {
                            sseRef.tell(a -> a.emit(ChatEvent.result(response.fullText())));
                        }
                    }
                );
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "vLLM call failed for turn " + turnNumber, e);
                if (sseRef != null) sseRef.tell(a -> a.emit(ChatEvent.error(e.getMessage())));
            }
        });
    }

    /** Applies a config patch. */
    public void applyConfigPatch(Map<String, Object> patch) {
        config.applyPatch(patch);
        LOG.info("Config updated: temperature=" + config.getTemperature()
                + " maxTokens=" + config.getMaxTokens()
                + " model=" + config.getModelId());
    }

    public ChatUiConfig  getConfig()      { return config; }

    /** Calls vLLM /v1/models and returns model IDs. Blocks the actor thread briefly. */
    public List<String> listModels() {
        return vllmClient.listModels(config.getVllmBaseUrl());
    }

    public void clearHistory() {
        messages.clear();
        turnCount = 0;
        if (ioLog != null && sessionId >= 0) {
            ioLog.endNamedSession(sessionId);
            sessionId = -1;
        }
        LOG.info("Session history cleared");
    }

    // Sends a message back to self by running the action directly (we are already in a
    // non-actor virtual thread, so we schedule it through the actor ref held by the system).
    // This field is set by ChatActorSystem after creation.
    ActorRef<ChatActor> selfRef;

    public void setSelfRef(ActorRef<ChatActor> ref) { this.selfRef = ref; }

    private void tell_self(java.util.function.Consumer<ChatActor> action) {
        if (selfRef != null) selfRef.tell(action);
        else action.accept(this); // fallback (single-threaded test context)
    }
}
