package com.scivicslab.chatui3.context;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Single session (single-user) for now; a future multi-session variant would key the store.</p>
 */
@ApplicationScoped
public class ConversationStore {

    private final List<Map<String, Object>> turns = new ArrayList<>();
    private volatile String systemPrompt = "You are a helpful assistant.";

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
            out.add(new LinkedHashMap<>(m));
        }
        out.add(message("user", currentUser));
        return out;
    }

    /** Returns a copy of the committed user/assistant turns (no system prompt). */
    public synchronized List<Map<String, Object>> historyTurns() {
        List<Map<String, Object>> out = new ArrayList<>(turns.size());
        for (Map<String, Object> m : turns) {
            out.add(new LinkedHashMap<>(m));
        }
        return out;
    }

    /** Commits a completed turn: appends the user message and the assistant reply as a pair. */
    public synchronized void commitTurn(String user, String assistant) {
        turns.add(message("user", user));
        turns.add(message("assistant", assistant));
    }

    /** Clears the conversation history (memory reset). */
    public synchronized void clear() {
        turns.clear();
    }

    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
