package com.scivicslab.chatui3.agent;

import com.scivicslab.chatui3.actor.SseActor;
import com.scivicslab.chatui3.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders a non-English prompt into English for DISPLAY ONLY (an English-study aid), shown as the
 * green "EN:" badge between the user's message and the assistant's reply. The ORIGINAL prompt is what
 * the agent loop actually receives — this translation is never sent to the main LLM.
 *
 * <p>Ported from chat-ui's {@code TranslatePreprocessor}. Runs on a virtual thread so it never delays
 * the answer, and emits a {@code translation} SSE event when it completes. Uses an OpenAI-compatible
 * vLLM endpoint (a small, fast model is sufficient).</p>
 *
 * <p>Config: {@code chatui3.translate.enabled}, {@code chatui3.translate.vllm-url},
 * {@code chatui3.translate.model}, {@code chatui3.translate.timeout-sec},
 * {@code chatui3.translate.max-length}.</p>
 */
@ApplicationScoped
public class PromptTranslator {

    private static final Logger LOG = Logger.getLogger(PromptTranslator.class.getName());

    // Two-version study aid: native English often drops the reserve/hedging typical of Japanese, so a
    // single "natural" line loses the original nuance. Show BOTH a natural (native-like) line and a
    // faithful (nuance-preserving) line so the learner can compare the two.
    private static final String SYSTEM_PROMPT =
            "You are a translation assistant for an English learner. For a non-English input, show BOTH "
          + "how a native speaker would naturally say it AND a version that stays faithful to the "
          + "original nuance, so the learner can compare.\n"
          + "Rules:\n"
          + "1. ALWAYS output in English. NEVER output in Chinese, Japanese, or any other language.\n"
          + "2. If the input is NOT English, output EXACTLY these two lines and nothing else:\n"
          + "Natural: <the way a native English speaker would naturally express this — idiomatic and "
          + "fluent. English tends to be more direct, so it is fine to drop Japanese-style hedging or "
          + "reserve if a native normally would.>\n"
          + "Faithful: <English that preserves the original nuance, tone, politeness level, reserve, "
          + "hedging, and emphasis — using English politeness/hedging devices (could you, would you "
          + "mind, it would be great if, I was wondering if) to convey the SAME degree of "
          + "directness/politeness as the original.>\n"
          + "3. If the input is ALREADY English, output it unchanged on a single line, with NO labels.\n"
          + "4. Output ONLY the specified line(s). No explanations, no extra commentary, no surrounding "
          + "quotes or code fences.\n"
          + "5. Your response MUST be in English regardless of your default language.";

    @ConfigProperty(name = "chatui3.translate.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "chatui3.translate.vllm-url",
            defaultValue = "http://192.0.2.10:8000/v1/chat/completions")
    String vllmUrl;

    @ConfigProperty(name = "chatui3.translate.model", defaultValue = "Qwen2.5-14B-Instruct-AWQ")
    String model;

    @ConfigProperty(name = "chatui3.translate.timeout-sec", defaultValue = "30")
    int timeoutSec;

    @ConfigProperty(name = "chatui3.translate.max-length", defaultValue = "500")
    int maxLength;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Fire-and-forget: translates {@code prompt} on a virtual thread and, if the text actually changed,
     * emits a {@code translation} SSE event via {@code sseRef}. Never throws; failures are logged and
     * silently skipped (the badge is a study aid, not part of the answer path).
     */
    public void translateAsync(String prompt, ActorRef<SseActor> sseRef) {
        if (!enabled || prompt == null || prompt.isBlank() || sseRef == null) {
            return;
        }
        if (prompt.length() > maxLength) {
            LOG.fine("prompt length " + prompt.length() + " > max-length " + maxLength + "; skip translation");
            return;
        }
        Thread.ofVirtual().name("prompt-translate").start(() -> {
            try {
                String english = callVllm(prompt);
                if (!english.isBlank() && !english.strip().equals(prompt.strip())) {
                    sseRef.tell(a -> a.emit(ChatEvent.translation(english)));
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "prompt translation failed (skipping badge): "
                        + VllmClientDescribe(e), e);
            }
        });
    }

    private static String VllmClientDescribe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private String callVllm(String userText) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", 1024)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(new JSONObject().put("role", "user").put("content", userText)));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("translate vLLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return new JSONObject(resp.body())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").strip();
    }
}
