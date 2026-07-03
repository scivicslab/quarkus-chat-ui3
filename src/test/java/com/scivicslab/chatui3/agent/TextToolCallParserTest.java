package com.scivicslab.chatui3.agent;

import com.scivicslab.chatui3.llm.VllmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TextToolCallParser — recover tool calls a model emitted as plain text")
class TextToolCallParserTest {

    // The exact malformed output captured from gemma-4 on the correction turn (with <|"|> junk tokens
    // and a bare <reason> without a closing tag).
    private static final String OBSERVED =
            "大変失礼いたしました。日付の認識を誤りました。\n\n"
          + "<function_calls>\n"
          + "<invoke name=\"web_search\">\n"
          + "<parameter name=\"query\">東京 天気予報 2026年7月4日 7月5日<|\"|></parameter>\n"
          + "<reason>本日7月4日の東京の天気予報を取得するため。<|\"|>\n"
          + "</invoke>\n"
          + "</function_calls>";

    @Test void detects_and_parses_the_observed_output() {
        assertTrue(TextToolCallParser.looksLikeTextToolCall(OBSERVED));
        List<VllmResponse.ToolCall> calls = TextToolCallParser.parse(OBSERVED);
        assertEquals(1, calls.size());
        VllmResponse.ToolCall c = calls.get(0);
        assertEquals("web_search", c.name());
        // The query value is extracted with the <|"|> junk stripped.
        assertTrue(c.arguments().contains("\"query\":\"東京 天気予報 2026年7月4日 7月5日\""),
                "args were: " + c.arguments());
        // The bare <reason> is captured too, junk stripped.
        assertTrue(c.arguments().contains("\"reason\":\"本日7月4日の東京の天気予報を取得するため。\""),
                "args were: " + c.arguments());
        // No leaked special token survives.
        assertFalse(c.arguments().contains("<|"), "junk token leaked: " + c.arguments());
    }

    @Test void strips_the_block_from_prose() {
        String prose = TextToolCallParser.stripToolCallBlocks(OBSERVED);
        assertEquals("大変失礼いたしました。日付の認識を誤りました。", prose);
    }

    @Test void plain_answer_is_not_a_tool_call() {
        String plain = "今日は2026年7月4日（土）です。天気は晴れです。";
        assertFalse(TextToolCallParser.looksLikeTextToolCall(plain));
        assertTrue(TextToolCallParser.parse(plain).isEmpty());
    }

    @Test void standard_parameter_only_form() {
        String s = "<invoke name=\"calc\"><parameter name=\"reason\">math</parameter>"
                 + "<parameter name=\"expression\">2+2</parameter></invoke>";
        List<VllmResponse.ToolCall> calls = TextToolCallParser.parse(s);
        assertEquals(1, calls.size());
        assertEquals("calc", calls.get(0).name());
        assertTrue(calls.get(0).arguments().contains("\"expression\":\"2+2\""));
    }

    @Test void multiple_invokes() {
        String s = "<function_calls>"
                 + "<invoke name=\"read\"><parameter name=\"path\">a.txt</parameter></invoke>"
                 + "<invoke name=\"read\"><parameter name=\"path\">b.txt</parameter></invoke>"
                 + "</function_calls>";
        List<VllmResponse.ToolCall> calls = TextToolCallParser.parse(s);
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).arguments().contains("a.txt"));
        assertTrue(calls.get(1).arguments().contains("b.txt"));
        assertEquals("text-call-0", calls.get(0).id());
        assertEquals("text-call-1", calls.get(1).id());
    }
}
