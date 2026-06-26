package com.scivicslab.chatui3.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link LlmProvider} that drives the {@code codex} CLI as a near-bare LLM via {@code codex exec}.
 *
 * <p>Runs non-interactively with a read-only sandbox ({@code -s read-only}, no shell side effects)
 * and writes the agent's final message to a temp file ({@code -o}) which is read back as the answer.
 * The conversation is owned by chat-ui3 and flattened into a single prompt fed on stdin; codex is
 * called statelessly. Like {@link ClaudeCodeProvider}, no OpenAI {@code tool_calls} are produced, so
 * the agent loop completes in one step (plain chat). v1 returns the final message without
 * incremental streaming or token usage.</p>
 */
public class CodexProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(CodexProvider.class.getName());

    private final IoLogStore ioLog;

    /** The in-flight CLI process, published so {@link #cancel()} can destroy it from another thread. */
    private volatile Process activeProcess;

    public CodexProvider(ObjectMapper mapper, IoLogStore ioLog) {
        // mapper is accepted for a uniform provider factory signature; codex needs no JSON parsing here.
        this.ioLog = ioLog;
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

        String model  = (config.getModelId() == null) ? "" : config.getModelId().trim();
        String prompt = flattenConversation(messages);

        Path outFile = null;
        String fullText;
        List<String> cmd;
        String requestStr;
        try {
            outFile = Files.createTempFile("codex-out-", ".txt");
            cmd = buildCommand(model, outFile);
            requestStr = "CODEX " + String.join(" ", cmd) + "\n\nPROMPT:\n" + prompt;
            LOG.info("codex request: model=" + (model.isBlank() ? "(default)" : model)
                    + ", " + messages.size() + " messages, prompt " + prompt.length() + " chars");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(System.getProperty("java.io.tmpdir")));
            pb.redirectErrorStream(true);   // fold stderr into stdout; we only drain it
            Process proc = pb.start();
            this.activeProcess = proc;

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            // Drain combined stdout/stderr (human-formatted progress) so the pipe never blocks.
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }

            int exit = proc.waitFor();
            fullText = Files.exists(outFile) ? Files.readString(outFile, StandardCharsets.UTF_8).strip() : "";
            if (fullText.isEmpty()) {
                if (exit != 0) {
                    throw new RuntimeException("codex CLI exited " + exit + ": " + out.toString().strip());
                }
                throw new RuntimeException("codex produced no output");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "codex request failed: " + e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "codex request failed: " + e.getMessage(), e);
            throw new RuntimeException("codex request failed: " + e.getMessage(), e);
        } finally {
            this.activeProcess = null;
            if (outFile != null) {
                try { Files.deleteIfExists(outFile); } catch (Exception ignore) { /* temp cleanup */ }
            }
        }

        // No incremental stream from this backend: emit the whole answer once.
        onDelta.accept(fullText);

        VllmResponse response = new VllmResponse(fullText, 0, 0, "", requestStr, List.of());
        if (ioLog != null && logCtx != null) {
            ioLog.record(logCtx.sessionId(), logCtx.node(), logCtx.label(),
                    "REQUEST:\n" + requestStr + "\n\nRESPONSE:\n" + fullText
                    + "\n\nUSAGE: promptTokens=0 completionTokens=0");
        }
        onComplete.accept(response);
    }

    @Override
    public List<String> listModels(String baseUrl) {
        // Best-effort preset list; the CLI uses its own configured default when none is selected.
        return List.of("gpt-5.4", "o3", "o4-mini");
    }

    @Override
    public void cancel() {
        Process p = this.activeProcess;
        if (p != null) p.destroy();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private List<String> buildCommand(String model, Path outFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("codex");
        cmd.add("exec");
        cmd.add("-s"); cmd.add("read-only");        // no shell side effects (bare LLM)
        cmd.add("--skip-git-repo-check");           // allow running outside a git repo
        cmd.add("-o"); cmd.add(outFile.toString()); // write the final agent message here
        if (!model.isBlank()) {
            cmd.add("-m"); cmd.add(model);
        }
        cmd.add("-");                                // read the prompt from stdin
        return cmd;
    }

    /** Flattens non-system messages into one prompt ({@code User:} / {@code Assistant:} prefixed). */
    private static String flattenConversation(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        String system = null;
        for (Map<String, Object> m : messages) {
            if ("system".equals(String.valueOf(m.get("role")))) {
                Object c = m.get("content");
                if (c != null) system = String.valueOf(c);
                break;
            }
        }
        // codex has no separate system-prompt flag here; prepend it as a leading instruction block.
        if (system != null && !system.isBlank()) {
            sb.append(system).append("\n\n");
        }
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
}
