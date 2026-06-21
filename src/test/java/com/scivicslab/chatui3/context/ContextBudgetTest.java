package com.scivicslab.chatui3.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("s_budget.01")
@DisplayName("ContextBudget — history fits the token budget; huge observations are truncated")
class ContextBudgetTest {

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Builds n (user, assistant) pairs, each message ~chars long, oldest first. */
    private static List<Map<String, Object>> history(int pairs, int chars) {
        List<Map<String, Object>> h = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            h.add(msg("user", "u" + i + "-" + "x".repeat(chars)));
            h.add(msg("assistant", "a" + i + "-" + "y".repeat(chars)));
        }
        return h;
    }

    @Test
    void fitHistory_dropsOldestPairs_keepsNewest_withinBudget() {
        List<Map<String, Object>> h = history(10, 300);            // 10 pairs, ~600 chars/pair
        int budget = ContextBudget.estimateTokens(history(3, 300)); // room for ~3 pairs

        List<Map<String, Object>> fit = ContextBudget.fitHistory(h, budget);

        assertTrue(ContextBudget.estimateTokens(fit) <= budget, "result must fit the budget");
        assertEquals(0, fit.size() % 2, "pairs are dropped together (even count)");
        assertTrue(fit.size() < h.size(), "older pairs were dropped");
        // Newest turn is kept; oldest is gone.
        assertTrue(((String) fit.get(fit.size() - 1).get("content")).startsWith("a9"), "newest assistant kept");
        assertFalse(((String) fit.get(0).get("content")).startsWith("u0"), "oldest user dropped");
    }

    @Test
    void fitHistory_keepsAll_whenBudgetAmple() {
        List<Map<String, Object>> h = history(4, 100);
        List<Map<String, Object>> fit = ContextBudget.fitHistory(h, 1_000_000);
        assertEquals(h.size(), fit.size(), "nothing trimmed when budget is ample");
    }

    @Test
    void fitHistory_dropsEverything_whenBudgetTiny() {
        List<Map<String, Object>> h = history(3, 200);
        List<Map<String, Object>> fit = ContextBudget.fitHistory(h, 1);
        assertEquals(0, fit.size(), "all pairs dropped when nothing fits");
    }

    @Test
    void truncateObservation_short_unchanged() {
        String s = "x".repeat(ContextBudget.OBS_THRESHOLD); // exactly at threshold = unchanged
        assertEquals(s, ContextBudget.truncateObservation(s));
    }

    @Test
    void truncateObservation_huge_keepsHeadAndTail_withMarker() {
        int n = ContextBudget.OBS_THRESHOLD + 50_000;
        String head = "H".repeat(ContextBudget.OBS_HEAD);
        String tail = "T".repeat(ContextBudget.OBS_TAIL);
        String middle = "M".repeat(n - ContextBudget.OBS_HEAD - ContextBudget.OBS_TAIL);
        String obs = head + middle + tail;

        String out = ContextBudget.truncateObservation(obs);

        assertTrue(out.length() < obs.length(), "truncated is shorter");
        assertTrue(out.startsWith(head), "head preserved");
        assertTrue(out.endsWith(tail), "tail preserved");
        assertTrue(out.contains("chars omitted"), "elision marker present");
        assertFalse(out.contains("M".repeat(ContextBudget.OBS_HEAD)), "the middle is gone");
    }
}
