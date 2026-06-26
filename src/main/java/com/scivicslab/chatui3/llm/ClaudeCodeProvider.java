package com.scivicslab.chatui3.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link LlmProvider} that drives the {@code claude} CLI as a near-bare LLM.
 *
 * <p>Calls {@code claude -p --output-format stream-json} with the agent harness minimised: built-in
 * tools are disabled ({@code --tools ""}), the system prompt is overridden with chat-ui3's own
 * ({@code --system-prompt}), sessions are not persisted, and either {@code --bare} (when
 * {@code ANTHROPIC_API_KEY} is set) or {@code --safe-mode} (otherwise — keeps OAuth/subscription auth
 * while disabling CLAUDE.md / hooks / plugins / MCP) is used. The conversation is owned by chat-ui3
 * and flattened into a single prompt each turn (claude is invoked statelessly, no {@code --resume}).</p>
 *
 * <p>The CLI never emits OpenAI {@code tool_calls}, so the returned {@link VllmResponse} carries text
 * only: against this backend the in-process agent loop finishes in one step (plain chat).</p>
 */
public class ClaudeCodeProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeProvider.class.getName());

    /** Default model alias when the UI has not selected one. */
    private static final String DEFAULT_MODEL = "sonnet";

    private final ObjectMapper mapper;
    private final IoLogStore ioLog;

    /** The in-flight CLI process, published so {@link #cancel()} can destroy it from another thread. */
    private volatile Process activeProcess;

    public ClaudeCodeProvider(ObjectMapper mapper, IoLogStore ioLog) {
        this.mapper = mapper;
        this.ioLog  = ioLog;
    }

    @Override
    public void streamCompletion(
            List<Map<String, Object>> messages,
            ChatUiConfig config,
            List<String> stop,
            List<Map<String, Object>> tools,   // ignored: the CLI does not accept OpenAI tool schemas
            LogContext logCtx,
            Consumer<String> onDelta,
            Consumer<VllmResponse> onComplete) {

        String model    = (config.getModelId() == null || config.getModelId().isBlank())
                ? DEFAULT_MODEL : config.getModelId();
        String system   = extractSystem(messages);
        String prompt   = flattenConversation(messages);
        boolean apiKey  = isApiKeyPresent();

        List<String> cmd = buildCommand(model, system, apiKey);
        String requestStr = "CLAUDE " + String.join(" ", cmd)
                + "\n\nSYSTEM:\n" + (system == null ? "" : system)
                + "\n\nPROMPT:\n" + prompt;
        LOG.info("claude request: model=" + model + (apiKey ? " (--bare, API key)" : " (--safe-mode, OAuth)")
                + ", " + messages.size() + " messages, prompt " + prompt.length() + " chars");

        StringBuilder fullText = new StringBuilder();
        int[] tokens = {0, 0};          // [inputTokens, outputTokens]
        String[] lastJson = {""};
        String[] errorMsg = {null};

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Neutral working directory so no project CLAUDE.md is picked up even in OAuth mode.
            pb.directory(new File(System.getProperty("java.io.tmpdir")));
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            this.activeProcess = proc;

            // Feed the flattened conversation on stdin, then close it so claude starts generating.
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            // Drain stderr on a separate thread so it can never block the stdout reader.
            StringBuilder stderr = new StringBuilder();
            Thread errThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = er.readLine()) != null) stderr.append(l).append('\n');
                } catch (Exception ignore) { /* process ended */ }
            });

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseStreamJsonLine(line, onDelta, fullText, tokens, lastJson, errorMsg);
                }
            }

            int exit = proc.waitFor();
            errThread.join(2000);
            if (exit != 0 && fullText.length() == 0) {
                String detail = errorMsg[0] != null ? errorMsg[0] : stderr.toString().trim();
                throw new RuntimeException("claude CLI exited " + exit
                        + (detail.isEmpty() ? "" : ": " + detail));
            }
            if (errorMsg[0] != null && fullText.length() == 0) {
                throw new RuntimeException("claude error: " + errorMsg[0]);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "claude request failed: " + e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "claude request failed: " + e.getMessage(), e);
            throw new RuntimeException("claude request failed: " + e.getMessage(), e);
        } finally {
            this.activeProcess = null;
        }

        VllmResponse response = new VllmResponse(
                fullText.toString(), tokens[0], tokens[1], lastJson[0], requestStr, List.of());

        if (ioLog != null && logCtx != null) {
            ioLog.record(logCtx.sessionId(), logCtx.node(), logCtx.label(),
                    "REQUEST:\n" + requestStr
                    + "\n\nRESPONSE:\n" + response.fullText()
                    + "\n\nUSAGE: promptTokens=" + response.promptTokens()
                    + " completionTokens=" + response.completionTokens());
        }

        onComplete.accept(response);
    }

    @Override
    public List<String> listModels(String baseUrl) {
        // Aliases accepted by claude --model; the CLI resolves each to the latest matching model.
        return List.of("opus", "sonnet", "haiku", "fable");
    }

    @Override
    public void cancel() {
        Process p = this.activeProcess;
        if (p != null) p.destroy();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private List<String> buildCommand(String model, String system, boolean apiKey) {
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        cmd.add("-p");
        cmd.add("--output-format"); cmd.add("stream-json");
        cmd.add("--verbose");                 // stream-json requires verbose in print mode
        cmd.add("--tools"); cmd.add("");      // disable all built-in tools (bare LLM)
        cmd.add("--no-session-persistence");
        cmd.add("--model"); cmd.add(model);
        if (system != null && !system.isBlank()) {
            cmd.add("--system-prompt"); cmd.add(system);
        }
        // Minimise the harness. With an API key, --bare is the leanest mode; without one, --safe-mode
        // disables CLAUDE.md/hooks/plugins/MCP while keeping OAuth/subscription auth working.
        cmd.add(apiKey ? "--bare" : "--safe-mode");
        return cmd;
    }

    private static boolean isApiKeyPresent() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k != null && !k.isBlank();
    }

    /** Returns the content of the first {@code system} message, or null if none. */
    private static String extractSystem(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            if ("system".equals(String.valueOf(m.get("role")))) {
                return String.valueOf(m.get("content"));
            }
        }
        return null;
    }

    /**
     * Flattens the non-system messages into one prompt string ({@code User:} / {@code Assistant:} /
     * {@code Tool:} prefixed turns). chat-ui3 owns the conversation, so the full history is sent each
     * turn and claude is called statelessly.
     */
    private static String flattenConversation(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.get("role"));
            if ("system".equals(role)) continue;
            Object content = m.get("content");
            if (content == null) continue;
            String label = switch (role) {
                case "assistant" -> "Assistant";
                case "tool"      -> "Tool";
                default          -> "User";
            };
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(label).append(": ").append(content);
        }
        return sb.toString();
    }

    private void parseStreamJsonLine(
            String line, Consumer<String> onDelta, StringBuilder fullText,
            int[] tokens, String[] lastJson, String[] errorMsg) {

        if (line.isBlank()) return;
        try {
            JsonNode node = mapper.readTree(line);
            String type = node.path("type").asText("");
            if ("assistant".equals(type)) {
                JsonNode content = node.path("message").path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("text".equals(block.path("type").asText(""))) {
                            String text = block.path("text").asText("");
                            if (!text.isEmpty()) {
                                fullText.append(text);
                                onDelta.accept(text);
                            }
                        }
                    }
                }
            } else if ("result".equals(type)) {
                JsonNode usage = node.path("usage");
                tokens[0] = usage.path("input_tokens").asInt(0);
                tokens[1] = usage.path("output_tokens").asInt(0);
                lastJson[0] = line;
                boolean isError = node.path("is_error").asBoolean(false)
                        || "error".equals(node.path("subtype").asText(""));
                String result = node.path("result").asText("");
                if (isError) {
                    errorMsg[0] = result.isEmpty() ? "claude reported an error" : result;
                } else if (fullText.length() == 0 && !result.isEmpty()) {
                    // No incremental assistant text was seen; fall back to the final result text.
                    fullText.append(result);
                    onDelta.accept(result);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to parse claude stream-json line: " + line, e);
        }
    }
}
