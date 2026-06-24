package com.scivicslab.chatui3.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.pojoactor.core.ActorRef;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for vLLM's OpenAI-compatible /v1/chat/completions endpoint.
 * Uses java.net.http.HttpClient so that vLLM-specific fields (e.g. stream_options)
 * are passed through without SDK limitations.
 */
public class VllmClient {

    private static final Logger LOG = Logger.getLogger(VllmClient.class.getName());

    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Batches the streamed response and logs it in time windows. May be null (e.g. in tests). */
    private final ActorRef<SseBatchLogger> batchRef;

    /** The in-flight response stream, published so {@link #cancel()} can close it from another thread. */
    private volatile InputStream activeStream;

    /** Shared complete-I/O log (s_iolog). May be null (logging disabled / tests). */
    private final IoLogStore ioLog;

    /** Per-call logging context: which session/node/label this vLLM call is recorded under. */
    public record LogContext(long sessionId, String node, String label) {}

    public VllmClient(ObjectMapper mapper) {
        this(mapper, null, null);
    }

    public VllmClient(ObjectMapper mapper, ActorRef<SseBatchLogger> batchRef) {
        this(mapper, batchRef, null);
    }

    public VllmClient(ObjectMapper mapper, ActorRef<SseBatchLogger> batchRef, IoLogStore ioLog) {
        this.mapper = mapper;
        this.batchRef = batchRef;
        this.ioLog = ioLog;
        // Force HTTP/1.1: vLLM does not support HTTP/2; the default HttpClient
        // tries HTTP/2 negotiation which causes the request body to be dropped.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Streams a chat completion from vLLM.
     * Blocks the calling thread (designed to run on a virtual thread).
     *
     * @param messages    conversation history in OpenAI format
     * @param config      current ChatUiConfig
     * @param onDelta     called with each text fragment as it arrives
     * @param onComplete  called once when streaming ends
     */
    public void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {
        streamCompletion(messages, config, null, onDelta, onComplete);
    }

    /**
     * As {@link #streamCompletion(List, ChatUiConfig, Consumer, Consumer)}, but with optional
     * {@code stop} sequences. The ReAct agent passes {@code ["Observation:"]} so generation halts at
     * the tool boundary (right after {@code Action Input:}) instead of the model fabricating its own
     * Observation and the rest of the answer.
     */
    public void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {
        streamCompletion(messages, config, stop, null, onDelta, onComplete);
    }

    public void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            LogContext logCtx,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {
        streamCompletion(messages, config, stop, null, logCtx, onDelta, onComplete);
    }

    /**
     * Worker overload with optional native {@code tools} and an I/O-log context. The full request +
     * response is recorded once here, so every caller (browser agent loop and entry A) logs uniformly
     * at this single point. When {@code tools} is non-null the model may answer with native
     * {@code tool_calls} (function calling) instead of text; those are parsed into the
     * {@link VllmResponse}.
     */
    public void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            List<Map<String, Object>> tools,
            LogContext logCtx,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {

        String url = config.getVllmBaseUrl() + "/v1/chat/completions";
        String requestBody = buildRequestBody(messages, config, stop, tools);

        // Compact request log: the full history can be large and is sent every turn, so by default
        // log only the shape (count, system+history present, current user, size). The full JSON is
        // available at FINE (raise the com.scivicslab.chatui3.llm level to see it).
        boolean hasSystem = !messages.isEmpty()
                && "system".equals(String.valueOf(messages.get(0).get("role")));
        String currentUser = messages.isEmpty() ? ""
                : String.valueOf(messages.get(messages.size() - 1).get("content"));
        String userPreview = currentUser.length() > 200 ? currentUser.substring(0, 200) + "…" : currentUser;
        LOG.info("vLLM request: " + messages.size() + " messages"
                + (hasSystem ? " (system+history)" : "")
                + ", " + requestBody.length() + " chars, user=" + userPreview);
        LOG.fine("vLLM request JSON (full): " + requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        StringBuilder fullText      = new StringBuilder();
        StringBuilder reasoning     = new StringBuilder();  // model chain-of-thought (reasoning_content)
        int[]         tokenCounts   = {0, 0};  // [promptTokens, completionTokens]
        String[]      lastChunkJson = {""};
        // Native tool_calls arrive across many SSE chunks (id+name in the first, arguments streamed as
        // fragments); accumulate them by their choices[].delta.tool_calls[].index.
        java.util.Map<Integer, ToolCallBuilder> toolCalls = new java.util.TreeMap<>();

        // Tee text fragments to the batch logger (which logs the response in ~3s windows as JSON)
        // alongside the caller's onDelta.
        Consumer<String> onDeltaTee = (batchRef == null) ? onDelta : fragment -> {
            onDelta.accept(fragment);
            batchRef.tell(b -> b.append(fragment));
        };
        if (batchRef != null) {
            batchRef.tell(SseBatchLogger::begin);
        }

        try {
            HttpResponse<InputStream> resp =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            LOG.info("vLLM response status: " + resp.statusCode());
            InputStream body = resp.body();
            this.activeStream = body;   // publish so cancel() can close it from another thread
            if (resp.statusCode() != 200) {
                String err = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                LOG.warning("vLLM error response (HTTP " + resp.statusCode() + "): " + err);
                throw new RuntimeException("vLLM returned HTTP " + resp.statusCode() + ": " + err);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseSseLine(line, onDeltaTee, fullText, reasoning, tokenCounts, lastChunkJson, toolCalls);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "vLLM request failed: " + e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            // A cancel() that closes activeStream surfaces here as an IOException; treat uniformly.
            LOG.log(Level.WARNING, "vLLM request failed: " + e.getMessage(), e);
            throw new RuntimeException("vLLM request failed: " + e.getMessage(), e);
        } finally {
            this.activeStream = null;
            if (batchRef != null) {
                batchRef.tell(SseBatchLogger::end);   // final flush of the windowed response log
            }
        }

        // The streamed text is logged in time windows by the batcher; here log only the final
        // usage chunk (tokens / finish_reason) so that response metadata is not lost.
        if (!lastChunkJson[0].isEmpty()) {
            LOG.info("vLLM response usage chunk: " + lastChunkJson[0]);
        }

        List<VllmResponse.ToolCall> finalToolCalls = new ArrayList<>();
        for (ToolCallBuilder b : toolCalls.values()) {
            if (b.name != null && !b.name.isBlank()) {
                finalToolCalls.add(new VllmResponse.ToolCall(
                        b.id, b.name, b.arguments.toString()));
            }
        }

        VllmResponse response = new VllmResponse(
                fullText.toString(),
                tokenCounts[0],
                tokenCounts[1],
                lastChunkJson[0],
                requestBody,
                finalToolCalls);

        // Single shared logging point: record the real prompt + response for THIS vLLM call. Both the
        // browser agent loop and entry A pass through here, so every call is captured uniformly.
        if (ioLog != null && logCtx != null) {
            StringBuilder toolPart = new StringBuilder();
            if (response.hasToolCalls()) {
                toolPart.append("\n\nTOOL_CALLS:");
                for (VllmResponse.ToolCall tc : response.toolCalls()) {
                    toolPart.append("\n  ").append(tc.name()).append(' ').append(tc.arguments());
                }
            }
            // The model's chain-of-thought (reasoning_content) is shown live in the browser but is not
            // part of fullText (the answer). Persist it here so the complete trace lives in H2 and the
            // browser's thinking view can be trimmed without losing it.
            String reasoningPart = reasoning.length() > 0 ? "\n\nREASONING:\n" + reasoning : "";
            ioLog.record(logCtx.sessionId(), logCtx.node(), logCtx.label(),
                    "REQUEST:\n" + requestBody
                    + "\n\nRESPONSE:\n" + response.fullText()
                    + reasoningPart
                    + toolPart
                    + "\n\nUSAGE: promptTokens=" + response.promptTokens()
                    + " completionTokens=" + response.completionTokens());
        }

        onComplete.accept(response);
    }

    /**
     * Aborts the in-flight streaming completion, if any, by closing its response stream.
     * Called from another thread (e.g. a watchdog / cancel) so a blocked read returns at once.
     */
    public void cancel() {
        InputStream s = this.activeStream;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
                // closing solely to interrupt the blocked read; failure here is irrelevant
            }
        }
    }

    /**
     * Returns the model IDs available on the vLLM server.
     */
    public List<String> listModels(String vllmBaseUrl) {
        String url = vllmBaseUrl + "/v1/models";
        LOG.info("vLLM request: GET " + url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            List<String> ids = new ArrayList<>();
            root.path("data").forEach(m -> ids.add(m.path("id").asText()));
            LOG.info("vLLM models response JSON: " + response.body());
            return ids;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "vLLM listModels failed from " + url + ": " + e.getMessage(), e);
            throw new RuntimeException("Failed to list models from " + vllmBaseUrl + ": " + e.getMessage(), e);
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private String buildRequestBody(List<Map<String, Object>> messages, ChatUiConfig config,
            List<String> stop, List<Map<String, Object>> tools) {
        Map<String, Object> req = new LinkedHashMap<>();
        if (!config.getModelId().isBlank()) {
            req.put("model", config.getModelId());
        }
        req.put("messages", messages);
        req.put("temperature", config.getTemperature());
        req.put("max_tokens", config.getMaxTokens());
        req.put("stream", true);
        req.put("stream_options", Map.of("include_usage", true));
        if (stop != null && !stop.isEmpty()) {
            req.put("stop", stop);   // halt generation at these sequences (ReAct tool boundary)
        }
        if (tools != null && !tools.isEmpty()) {
            // Native function calling: let the model decide whether to call a tool or answer.
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        try {
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    /** Accumulates one streamed native tool call across SSE chunks (id/name first, arguments piecewise). */
    private static final class ToolCallBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private void parseSseLine(
            String line,
            Consumer<String> onDelta,
            StringBuilder fullText,
            StringBuilder reasoningBuf,
            int[] tokenCounts,
            String[] lastChunkJson,
            Map<Integer, ToolCallBuilder> toolCalls) {

        if (!line.startsWith("data: ")) return;
        String data = line.substring(6).trim();
        if ("[DONE]".equals(data)) return;

        try {
            JsonNode node = mapper.readTree(data);

            // Extract text delta from choices[0].delta.content
            JsonNode choices = node.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta");
                JsonNode content = delta.path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    String fragment = content.asText();
                    if (!fragment.isEmpty()) {
                        fullText.append(fragment);
                        onDelta.accept(fragment);
                    }
                }
                // With --reasoning-parser, the model's chain-of-thought arrives separately as
                // reasoning_content (not content). Stream it to the live "thinking" view but do NOT
                // append it to fullText, so the committed answer stays the content only.
                JsonNode reasoning = delta.path("reasoning_content");
                if (!reasoning.isMissingNode() && !reasoning.isNull()) {
                    String rf = reasoning.asText();
                    if (!rf.isEmpty()) {
                        reasoningBuf.append(rf);   // captured for the full I/O log (REASONING section)
                        onDelta.accept(rf);
                    }
                }
                // Native tool_calls deltas: each carries an index; id+name come once, arguments stream.
                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    for (JsonNode tc : tcs) {
                        int idx = tc.path("index").asInt(0);
                        ToolCallBuilder b = toolCalls.computeIfAbsent(idx, k -> new ToolCallBuilder());
                        if (tc.hasNonNull("id")) b.id = tc.get("id").asText();
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) b.name = fn.get("name").asText();
                        if (fn.hasNonNull("arguments")) b.arguments.append(fn.get("arguments").asText());
                    }
                }
            }

            // Extract usage from final chunk (stream_options.include_usage=true)
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                tokenCounts[0] = usage.path("prompt_tokens").asInt();
                tokenCounts[1] = usage.path("completion_tokens").asInt();
                lastChunkJson[0] = data;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to parse SSE line: " + line, e);
        }
    }
}
