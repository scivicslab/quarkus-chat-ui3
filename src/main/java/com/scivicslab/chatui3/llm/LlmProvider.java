package com.scivicslab.chatui3.llm;

import com.scivicslab.chatui3.config.ChatUiConfig;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Abstraction over the LLM backend selected at startup via {@code chatui3.backend}.
 *
 * <p>The default backend talks to a vLLM OpenAI-compatible endpoint ({@link VllmClient}); the
 * alternatives shell out to the {@code claude} ({@link ClaudeCodeProvider}) or {@code codex}
 * ({@link CodexProvider}) CLIs as near-bare LLMs (their own agent harness minimised). All three
 * implement this single seam: callers assemble OpenAI-style {@code messages} and receive a
 * {@link VllmResponse} (text plus optional native {@code tool_calls}).</p>
 *
 * <p>Only the vLLM backend returns native {@code tool_calls}; the CLI backends always return plain
 * text (no tool calls), so the in-process agent loop completes in a single step against them — the
 * chat-ui3 tool set (read/calc/web_search/…) is effectively a vLLM-only capability.</p>
 */
public interface LlmProvider {

    /**
     * Streams one chat completion. Blocks the calling thread (run on a virtual thread). When
     * {@code tools} is non-null the model may answer with native {@code tool_calls} instead of text;
     * those are returned in the {@link VllmResponse} (CLI backends ignore {@code tools}/{@code stop}).
     */
    void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            List<Map<String, Object>> tools,
            LogContext logCtx,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete);

    /** Convenience overload without native tools (single-shot path). */
    default void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            LogContext logCtx,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {
        streamCompletion(messages, config, stop, null, logCtx, onDelta, onComplete);
    }

    /**
     * Returns the model ids the UI offers. {@code baseUrl} is the vLLM base URL (the vLLM backend
     * queries {@code /v1/models}); CLI backends ignore it and return a fixed alias list.
     */
    List<String> listModels(String baseUrl);

    /** Aborts any in-flight completion (called from another thread, e.g. cancel / teardown). */
    void cancel();
}
