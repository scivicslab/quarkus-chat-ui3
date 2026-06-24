package com.scivicslab.chatui3.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("s_iolog.view")
@DisplayName("IoLogView — agent-axis grouping and Stream shaping (filter/level/preview/limit)")
class IoLogViewTest {

    private static int idSeq = 0;

    private static LogEntry entry(String nodeId, String label, LogLevel level, String message) {
        return new LogEntry(++idSeq, 1L, LocalDateTime.of(2026, 6, 23, 10, 0, idSeq),
                nodeId, label, "output", level, message, 0, 0L);
    }

    private static IoLogView.Filter filter(String label, String level, String q, int limit) {
        return new IoLogView.Filter(null, label, level, q, null, null, limit);
    }

    @Test
    @DisplayName("agentsOf groups by node_id with counts, sorted by name")
    void agentsOf_groupsByNodeWithCounts() {
        List<LogEntry> raw = List.of(
                entry("worker", "turn1/step1/llm", LogLevel.INFO, "a"),
                entry("agent", "turn1/step1/llm", LogLevel.INFO, "b"),
                entry("agent", "turn1/step1/tool", LogLevel.INFO, "c"));

        List<IoLogView.AgentInfo> agents = IoLogView.agentsOf(raw);

        assertEquals(2, agents.size());
        assertEquals("agent", agents.get(0).agent());   // sorted by name
        assertEquals(2, agents.get(0).lines());
        assertEquals("worker", agents.get(1).agent());
        assertEquals(1, agents.get(1).lines());
    }

    @Test
    @DisplayName("label filter keeps only matching labels (e.g. /llm excludes /tool)")
    void shape_filtersByLabel() {
        List<LogEntry> raw = List.of(
                entry("agent", "turn1/step1/llm", LogLevel.INFO, "req"),
                entry("agent", "turn1/step1/tool", LogLevel.INFO, "tool"),
                entry("agent", "turn1/step2/llm", LogLevel.INFO, "req2"));

        IoLogView.Page page = IoLogView.shape(raw, filter("/llm", null, null, 0));

        assertEquals(2, page.returned());
        assertTrue(page.entries().stream().allMatch(e -> e.label().contains("llm")));
        assertFalse(page.limited());
    }

    @Test
    @DisplayName("text filter q matches message content (case-insensitive)")
    void shape_filtersByText() {
        List<LogEntry> raw = List.of(
                entry("agent", "turn1/step1/tool", LogLevel.INFO, "OBSERVATION: 437"),
                entry("agent", "turn1/step1/llm", LogLevel.INFO, "REQUEST: ..."));

        IoLogView.Page page = IoLogView.shape(raw, filter(null, null, "observation", 0));

        assertEquals(1, page.returned());
        assertTrue(page.entries().get(0).message().contains("OBSERVATION"));
    }

    @Test
    @DisplayName("level filter keeps entries at or above the threshold")
    void shape_filtersByLevel() {
        List<LogEntry> raw = List.of(
                entry("agent", "a", LogLevel.INFO, "info"),
                entry("agent", "b", LogLevel.ERROR, "boom"));

        IoLogView.Page page = IoLogView.shape(raw, filter(null, "WARN", null, 0));

        assertEquals(1, page.returned());
        assertEquals("ERROR", page.entries().get(0).level());
    }

    @Test
    @DisplayName("limit caps results and flags 'limited' when more matched")
    void shape_capsAndFlagsLimited() {
        List<LogEntry> raw = List.of(
                entry("agent", "a", LogLevel.INFO, "1"),
                entry("agent", "b", LogLevel.INFO, "2"),
                entry("agent", "c", LogLevel.INFO, "3"));

        IoLogView.Page page = IoLogView.shape(raw, filter(null, null, null, 2));

        assertEquals(2, page.returned());
        assertTrue(page.limited());
    }

    @Test
    @DisplayName("huge messages are previewed (truncated) with full length reported")
    void shape_previewsHugeMessage() {
        String big = "x".repeat(IoLogView.PREVIEW_CHARS + 100);
        IoLogView.Page page = IoLogView.shape(
                List.of(entry("agent", "turn1/step1/llm", LogLevel.INFO, big)),
                filter(null, null, null, 0));

        IoLogView.Entry e = page.entries().get(0);
        assertTrue(e.truncated());
        assertEquals(IoLogView.PREVIEW_CHARS, e.message().length());
        assertEquals(IoLogView.PREVIEW_CHARS + 100, e.chars());
    }

    @Test
    @DisplayName("USAGE tokens are parsed from the tail of a huge llm entry (beyond the preview)")
    void shape_parsesTokensFromUsageBeyondPreview() {
        // Build a /llm body where USAGE sits far past PREVIEW_CHARS, like a real request blob.
        String body = "REQUEST:\n" + "{\"messages\":...}".repeat(200)
                + "\n\nRESPONSE:\nhello"
                + "\n\nUSAGE: promptTokens=5083 completionTokens=62";
        IoLogView.Page page = IoLogView.shape(
                List.of(entry("agent", "turn1/step2/llm", LogLevel.INFO, body)),
                filter(null, null, null, 0));

        IoLogView.Entry e = page.entries().get(0);
        assertTrue(e.truncated());                 // preview cut, but tokens still parsed from full body
        assertEquals(5083, e.promptTokens());
        assertEquals(62, e.completionTokens());
    }

    @Test
    @DisplayName("entries without a USAGE line report -1 tokens (e.g. tool calls)")
    void shape_toolEntryHasNoTokens() {
        IoLogView.Page page = IoLogView.shape(
                List.of(entry("agent", "turn1/step1/tool", LogLevel.INFO,
                        "TOOL: read\nINPUT:\ndocs\nOBSERVATION:\n...")),
                filter(null, null, null, 0));

        IoLogView.Entry e = page.entries().get(0);
        assertEquals(-1, e.promptTokens());
        assertEquals(-1, e.completionTokens());
    }

    @Test
    @DisplayName("parseTokenCount: reads the integer after the key, -1 when absent")
    void parseTokenCount_basics() {
        assertEquals(42, IoLogView.parseTokenCount("USAGE: promptTokens=42 completionTokens=7", "promptTokens="));
        assertEquals(7, IoLogView.parseTokenCount("USAGE: promptTokens=42 completionTokens=7", "completionTokens="));
        assertEquals(-1, IoLogView.parseTokenCount("no usage here", "promptTokens="));
        assertEquals(-1, IoLogView.parseTokenCount(null, "promptTokens="));
    }

    @Test
    @DisplayName("between: extracts a section up to the next marker")
    void between_extractsSection() {
        String m = "REQUEST:\nabc\n\nRESPONSE:\nhello\n\nTOOL_CALLS:\n  read {x}\n\nUSAGE: promptTokens=1 completionTokens=2";
        assertEquals("hello", IoLogView.between(m, "RESPONSE:", "REASONING:", "TOOL_CALLS:", "USAGE:"));
        assertEquals("read {x}", IoLogView.between(m, "TOOL_CALLS:", "USAGE:"));
        assertEquals("", IoLogView.between(m, "REASONING:", "USAGE:"));   // absent marker -> empty
    }

    @Test
    @DisplayName("traceOf: groups by turn and reconstructs the loop (llm tool-call -> tool -> final answer)")
    void traceOf_reconstructsLoop() {
        String req1 = "{\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"read the docs folder\"}],\"temperature\":0}";
        List<LogEntry> raw = List.of(
            entry("agent", "turn1/step1/llm", LogLevel.INFO,
                "REQUEST:\n" + req1 + "\n\nRESPONSE:\n\n\nTOOL_CALLS:\n  read {\"path\": \"docs\"}\n\nUSAGE: promptTokens=100 completionTokens=10"),
            entry("agent", "turn1/step1/tool", LogLevel.INFO,
                "TOOL: read\nINPUT:\ndocs\nOBSERVATION:\n" + "X".repeat(500)),
            entry("agent", "turn1/step2/llm", LogLevel.INFO,
                "REQUEST:\n{\"messages\":[]}\n\nRESPONSE:\nThe answer is foo.\n\nUSAGE: promptTokens=300 completionTokens=20"));

        List<IoLogView.TraceTurn> turns = IoLogView.traceOf(raw);
        assertEquals(1, turns.size());
        IoLogView.TraceTurn t = turns.get(0);
        assertEquals(1, t.turn());
        assertEquals("read the docs folder", t.userPrompt());   // pulled from step1's REQUEST
        assertEquals(3, t.steps().size());

        IoLogView.TraceStep s1 = t.steps().get(0);   // llm: bare tool call, no thought
        assertEquals(raw.get(0).getId(), s1.id());    // carries the entry id for full-entry drill-down
        assertEquals("llm", s1.kind());
        assertEquals("", s1.thought());
        assertTrue(s1.toolCalls().contains("read"));
        assertFalse(s1.finalAnswer());
        assertEquals(100, s1.promptTokens());

        IoLogView.TraceStep s2 = t.steps().get(1);   // tool: name/input + digest + full size
        assertEquals("tool", s2.kind());
        assertEquals("read", s2.toolName());
        assertEquals("docs", s2.toolInput());
        assertEquals(500, s2.obsChars());
        assertEquals(IoLogView.OBS_DIGEST, s2.observation().length());

        IoLogView.TraceStep s3 = t.steps().get(2);   // llm: final answer (no tool calls)
        assertEquals("The answer is foo.", s3.thought());
        assertTrue(s3.finalAnswer());
    }

    @Test
    @DisplayName("extractReasons: pulls the reason argument(s) from a TOOL_CALLS section")
    void extractReasons_pullsReasons() {
        String tc = "read {\"reason\": \"need the directory listing\", \"path\": \"docs\"}\n"
                  + "read {\"reason\": \"check the second folder too\", \"path\": \"docs2\"}";
        assertEquals("need the directory listing  /  check the second folder too",
                IoLogView.extractReasons(tc));
        assertEquals("", IoLogView.extractReasons("read {\"path\": \"docs\"}"));   // no reason field
        assertEquals("", IoLogView.extractReasons(""));
    }

    @Test
    @DisplayName("extractUserPrompt: returns the LAST user message (the current question, not history)")
    void extractUserPrompt_lastUser() {
        String req = "{\"messages\":["
            + "{\"role\":\"system\",\"content\":\"s\"},"
            + "{\"role\":\"user\",\"content\":\"old question\"},"
            + "{\"role\":\"assistant\",\"content\":\"old answer\"},"
            + "{\"role\":\"user\",\"content\":\"the current question\"}]}";
        assertEquals("the current question", IoLogView.extractUserPrompt(req));
        assertEquals("", IoLogView.extractUserPrompt("not json"));
        assertEquals("", IoLogView.extractUserPrompt(""));
    }
}
