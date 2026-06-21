package com.scivicslab.chatui3.llm;

/**
 * Completion result from one vLLM call. Passed to the onComplete callback in VllmClient.
 * requestBody and fullText are recorded verbatim into the complete I/O log (s_iolog).
 */
public record VllmResponse(
        String fullText,
        int promptTokens,
        int completionTokens,
        String rawResponseJson,
        String requestBody
) {}
