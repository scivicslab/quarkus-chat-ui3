package com.scivicslab.chatui3.llm;

/**
 * Per-call logging context: which session/node/label one LLM call is recorded under in the complete
 * I/O log (s_iolog). Part of the {@link LlmProvider} contract so every backend logs uniformly.
 */
public record LogContext(long sessionId, String node, String label) {}
