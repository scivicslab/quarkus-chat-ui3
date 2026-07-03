package com.scivicslab.chatui3.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.iolog.IoLogStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side session conversation history for the browser agent loop (entry B).
 *
 * <p>Holds the system prompt and the committed user/assistant turns, and assembles the
 * {@code messages} sent to the LLM each turn as {@code [system] + history + current user}.</p>
 *
 * <p>Only completed turns are committed — and a user message and its assistant reply are added as a
 * pair ({@link #commitTurn}) — so the history is always a well-formed alternating sequence. A turn
 * that errors or is cancelled commits nothing, leaving no dangling user message.</p>
 *
 * <p><b>Persistence / restart-resume.</b> The conversation is a small, purpose-built store of just
 * the confirmed turns, persisted to H2 (one row, table {@code conversation_state}) so it survives a
 * restart. On startup the turns are reloaded and the matching I/O-log session is resumed
 * ({@link IoLogStore#resumeSession}), so the conversation continues in the same session rather than
 * starting fresh. This is deliberately a separate store from the complete I/O log (see
 * {@code ConversationContext} design): the log is the full immutable record, this is the trimmed,
 * mutable working memory the model sees — kept apart but both persisted in the same DB.</p>
 *
 * <p>Single session (single-user) for now; a future multi-session variant would key the store.</p>
 */
@ApplicationScoped
public class ConversationStore {

    private static final Logger LOG = Logger.getLogger(ConversationStore.class.getName());
    private static final String KEY = "browser";   // single-user: one persisted conversation row

    private final List<Map<String, Object>> turns = new ArrayList<>();
    private volatile String systemPrompt = "You are a helpful assistant.";

    @Inject
    ObjectMapper mapper;

    @Inject
    IoLogStore ioLog;

    // ── Conversation API ──────────────────────────────────────────────────────

    /**
     * Builds the messages to send for one turn: {@code system + committed history + current user}.
     * Does NOT mutate the store; entries are copied so the LLM call's list is independent.
     */
    public synchronized List<Map<String, Object>> snapshotWith(String currentUser) {
        List<Map<String, Object>> out = new ArrayList<>(turns.size() + 2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            out.add(message("system", systemPrompt));
        }
        for (Map<String, Object> m : turns) {
            out.add(cleanCopy(m));   // only role/content reach the LLM (drop our metadata, e.g. source)
        }
        out.add(message("user", currentUser));
        return out;
    }

    /** Returns the committed user/assistant turns as clean {role,content} (no system prompt, no metadata). */
    public synchronized List<Map<String, Object>> historyTurns() {
        List<Map<String, Object>> out = new ArrayList<>(turns.size());
        for (Map<String, Object> m : turns) {
            out.add(cleanCopy(m));
        }
        return out;
    }

    /** One committed turn for the chat view: its number, the question (+ who sent it), and the answer. */
    public record Turn(int turn, String question, String source, String answer) {}

    /**
     * The conversation as the model actually has it (the authoritative context), for rendering the
     * left chat pane. Each pair becomes one numbered turn; {@code source} records who entered the
     * prompt ({@code "browser"} = the user in this UI, anything else e.g. {@code "api"} = entered by
     * another client), or null for older turns recorded before sources were tracked.
     */
    public synchronized List<Turn> conversation() {
        List<Turn> out = new ArrayList<>();
        for (int i = 0; i + 1 < turns.size(); i += 2) {
            Map<String, Object> u = turns.get(i);
            Map<String, Object> a = turns.get(i + 1);
            Object src = u.get("source");
            out.add(new Turn(i / 2 + 1, str(u.get("content")),
                    src == null ? null : src.toString(), str(a.get("content"))));
        }
        return out;
    }

    /** Commits a completed turn: appends the user message (tagged with its source) and the reply. */
    public synchronized void commitTurn(String user, String assistant, String source) {
        Map<String, Object> u = message("user", user);
        if (source != null && !source.isBlank()) {
            u.put("source", source);   // our own metadata; stripped before the map reaches the LLM
        }
        turns.add(u);
        turns.add(message("assistant", assistant));
        persist();
    }

    /** Clears the conversation history (memory reset) and removes the persisted row. */
    public synchronized void clear() {
        turns.clear();
        deletePersisted();
    }

    public synchronized void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
        persist();
    }

    // ── Persistence / resume ──────────────────────────────────────────────────

    void onStart(@Observes StartupEvent event) {
        // All H2 access is owned by IoLogStore, so conversation_state lives in the SAME per-port DB file
        // as the sessions/logs tables. This is why the resumed session id is always valid for this
        // instance's log DB; when it lived in a separate (port-less) file the id was foreign and every
        // log write failed the sessions foreign key (Sessions tab: ids but no turns).
        load();
    }

    private synchronized void load() {
        IoLogStore.ConversationState st = ioLog.loadConversationState(KEY);
        if (st == null) {
            return;   // no prior conversation to resume
        }
        try {
            if (st.systemPrompt() != null && !st.systemPrompt().isBlank()) {
                systemPrompt = st.systemPrompt();
            }
            if (st.turnsJson() != null && !st.turnsJson().isBlank()) {
                List<Map<String, Object>> restored =
                        mapper.readValue(st.turnsJson(), new TypeReference<List<Map<String, Object>>>() {});
                turns.clear();
                turns.addAll(restored);
            }
            if (st.sessionId() > 0) {
                ioLog.resumeSession(st.sessionId());   // keep appending to the same I/O-log session
            }
            LOG.info("Resumed conversation: " + (turns.size() / 2) + " turn(s), log session " + st.sessionId());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to restore conversation state", e);
        }
    }

    /** Upserts the single conversation row through IoLogStore. Called within synchronized methods. */
    private void persist() {
        try {
            ioLog.saveConversationState(KEY, ioLog.currentSessionId(), systemPrompt,
                    mapper.writeValueAsString(turns));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to persist conversation state", e);
        }
    }

    private void deletePersisted() {
        ioLog.clearConversationState(KEY);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** A {role,content}-only copy of a stored turn map, dropping any metadata (e.g. source). */
    private static Map<String, Object> cleanCopy(Map<String, Object> m) {
        return message(str(m.get("role")), str(m.get("content")));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

