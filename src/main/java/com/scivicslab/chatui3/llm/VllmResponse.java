package com.scivicslab.chatui3.llm;

import java.util.List;

/**
 * Completion result from one vLLM call. Passed to the onComplete callback in VllmClient.
 * requestBody and fullText are recorded verbatim into the complete I/O log (s_iolog).
 *
 * <p>{@code toolCalls} carries the model's NATIVE function calls (OpenAI {@code tool_calls}) when the
 * model decided to call a tool instead of answering; it is empty for a plain text answer.</p>
 */
public record VllmResponse(
        String fullText,
        int promptTokens,
        int completionTokens,
        String rawResponseJson,
        String requestBody,
        List<ToolCall> toolCalls
) {
    /** One native function call requested by the model. {@code arguments} is the raw JSON string. */
    public record ToolCall(String id, String name, String arguments) {}

    /** True when the model asked to call at least one tool (rather than producing a final answer). */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
