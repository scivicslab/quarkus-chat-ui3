package com.scivicslab.chatui3.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for keeping the LLM request within a token budget (s_budget).
 *
 * <p>Trimming applies ONLY to what the model receives. The complete I/O log (s_iolog) records the
 * full, untrimmed content separately, so nothing is lost from the record.</p>
 *
 * <p>Token counts are a deliberately conservative char-based estimate (no tokenizer): over-estimating
 * tokens makes us trim a little earlier, which keeps us safely under the real limit.</p>
 */
public final class ContextBudget {

    private ContextBudget() {}

    /**
     * Chars per token for the estimate. 2.0 stays conservative even for Japanese (which is denser in
     * tokens than English ~4 chars/token), so the estimate never under-counts and the request stays
     * under the model's real limit even at a large context window (lower = trims earlier). English
     * conversations therefore trim a little earlier than strictly necessary — an acceptable trade for
     * not overflowing the model on Japanese-heavy context.
     */
    static final double CHARS_PER_TOKEN = 2.0;
    /** Per-message overhead (role + formatting) added to each message's content estimate. */
    static final int PER_MESSAGE_OVERHEAD = 4;

    /** A tool observation larger than this many characters is truncated before the model sees it. */
    public static final int OBS_THRESHOLD = 8000;
    /** Characters kept from the head of a truncated observation. */
    public static final int OBS_HEAD = 6000;
    /** Characters kept from the tail of a truncated observation. */
    public static final int OBS_TAIL = 1000;

    /** Rough token estimate from character count. */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(s.length() / CHARS_PER_TOKEN);
    }

    /** Estimated tokens for a list of OpenAI-format messages (content + per-message overhead). */
    public static int estimateTokens(List<Map<String, Object>> messages) {
        int sum = 0;
        for (Map<String, Object> m : messages) {
            Object content = m.get("content");
            sum += estimateTokens(content == null ? "" : content.toString());
            sum += PER_MESSAGE_OVERHEAD;
        }
        return sum;
    }

    /**
     * Drops the OLDEST (user, assistant) pairs from {@code history} until its estimated tokens fit
     * {@code budgetTokens}. Newest turns are kept; pairs are dropped together so the alternating
     * structure stays intact. The input list is not mutated; a new list is returned.
     */
    public static List<Map<String, Object>> fitHistory(List<Map<String, Object>> history, int budgetTokens) {
        List<Map<String, Object>> out = new ArrayList<>(history);
        while (estimateTokens(out) > budgetTokens && out.size() >= 2) {
            out.remove(0);   // oldest user
            out.remove(0);   // its assistant
        }
        return out;
    }

    /**
     * Truncates a large tool observation to head + tail with an elision marker, for the copy the
     * model sees. Callers log the FULL observation separately (s_iolog invariant). Short observations
     * are returned unchanged.
     */
    public static String truncateObservation(String obs) {
        if (obs == null || obs.length() <= OBS_THRESHOLD) {
            return obs;
        }
        int omitted = obs.length() - OBS_HEAD - OBS_TAIL;
        return obs.substring(0, OBS_HEAD)
                + "\n…(" + omitted + " chars omitted)…\n"
                + obs.substring(obs.length() - OBS_TAIL);
    }
}
