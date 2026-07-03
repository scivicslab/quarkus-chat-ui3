package com.scivicslab.chatui3.agent;

import com.scivicslab.chatui3.llm.VllmResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser for tool calls a model emits as PLAIN TEXT instead of via the native OpenAI
 * {@code tool_calls} channel. gemma-4 (and other models) sometimes fall out of the native format in
 * multi-turn contexts and write the call out in the Anthropic-style XML block, e.g.:
 *
 * <pre>{@code
 * <function_calls>
 * <invoke name="web_search">
 * <parameter name="query">tokyo weather</parameter>
 * <reason>need the forecast</reason>
 * </invoke>
 * </function_calls>
 * }</pre>
 *
 * <p>vLLM's tool parser does not recognise this shape, so the agent loop would otherwise commit the raw
 * XML as the final answer. This parser extracts the {@code invoke} blocks so the loop can execute them
 * like real tool calls. It is deliberately tolerant: it accepts missing {@code </parameter>} tags, a
 * bare {@code <reason>...} without a close tag, and strips the stray {@code <|"|>} special-token junk
 * the model interleaves.</p>
 */
final class TextToolCallParser {

    private TextToolCallParser() {}

    // Each <invoke name="TOOL"> ... </invoke> block (DOTALL so it spans newlines).
    private static final Pattern INVOKE =
            Pattern.compile("<invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</invoke>", Pattern.DOTALL);
    // A <parameter name="KEY">VALUE</parameter> pair; VALUE ends at </parameter>, the next <parameter,
    // <reason>, or </invoke> — tolerating a missing close tag.
    private static final Pattern PARAM = Pattern.compile(
            "<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)(?:</parameter>|(?=<parameter)|(?=<reason)|(?=</invoke>)|$)",
            Pattern.DOTALL);
    // A bare <reason>VALUE (with or without a closing tag) some outputs use instead of a parameter.
    private static final Pattern REASON = Pattern.compile(
            "<reason\\s*>(.*?)(?:</reason>|(?=</invoke>)|(?=<parameter)|$)", Pattern.DOTALL);
    // Stray special tokens the model leaks into values, e.g. <|"|>.
    private static final Pattern JUNK_TOKEN = Pattern.compile("<\\|[^>]*\\|>");

    /** True when {@code content} contains at least one textual {@code <invoke ...>} tool-call block. */
    static boolean looksLikeTextToolCall(String content) {
        return content != null && content.contains("<invoke ") && content.contains("name=");
    }

    /**
     * Parses every textual tool call in {@code content}. Returns synthetic {@link VllmResponse.ToolCall}s
     * (ids {@code text-call-0}, {@code text-call-1}, …) whose {@code arguments} is a JSON object built
     * from the extracted parameters, ready to feed to the same execution path as native calls.
     *
     * @return the parsed calls, or an empty list when none are present
     */
    static List<VllmResponse.ToolCall> parse(String content) {
        List<VllmResponse.ToolCall> calls = new ArrayList<>();
        if (!looksLikeTextToolCall(content)) return calls;

        Matcher inv = INVOKE.matcher(content);
        int idx = 0;
        while (inv.find()) {
            String name = inv.group(1).trim();
            String body = inv.group(2);

            Map<String, String> args = new LinkedHashMap<>();
            Matcher pm = PARAM.matcher(body);
            while (pm.find()) {
                args.put(pm.group(1).trim(), clean(pm.group(2)));
            }
            Matcher rm = REASON.matcher(body);
            if (rm.find() && !args.containsKey("reason")) {
                args.put("reason", clean(rm.group(1)));
            }
            calls.add(new VllmResponse.ToolCall("text-call-" + idx++, name, toJson(args)));
        }
        return calls;
    }

    /** Strips leaked special tokens and surrounding whitespace from an extracted value. */
    private static String clean(String v) {
        if (v == null) return "";
        return JUNK_TOKEN.matcher(v).replaceAll("").trim();
    }

    /** Minimal JSON object serialisation for the extracted string parameters. */
    private static String toJson(Map<String, String> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Removes the textual {@code <function_calls>…</function_calls>} block(s) from prose to keep. */
    static String stripToolCallBlocks(String content) {
        if (content == null) return "";
        String out = content.replaceAll("(?s)<function_calls>.*?</function_calls>", "");
        // Some outputs omit the wrapper and emit a bare <invoke>…</invoke>; drop those too.
        out = out.replaceAll("(?s)<invoke\\s+name=\"[^\"]+\"\\s*>.*?</invoke>", "");
        return JUNK_TOKEN.matcher(out).replaceAll("").trim();
    }
}
