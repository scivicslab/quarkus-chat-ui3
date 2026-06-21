package com.scivicslab.chatui3.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("s_config.01")
@DisplayName("ChatUiConfig — partial patch updates only specified fields")
class ChatUiConfigTest {

    private ChatUiConfig defaults() {
        return new ChatUiConfig("http://192.0.2.10:8000", "", 0.7, 4096);
    }

    @Test
    void applyPatch_withTemperature_updatesOnlyTemperature() {
        ChatUiConfig cfg = defaults();
        cfg.applyPatch(Map.of("temperature", 0.2));

        assertEquals(0.2, cfg.getTemperature(), 0.001);
        assertEquals(4096, cfg.getMaxTokens());   // unchanged
        assertEquals("",   cfg.getModelId());      // unchanged
    }

    @Test
    void applyPatch_withMaxTokens_updatesOnlyMaxTokens() {
        ChatUiConfig cfg = defaults();
        cfg.applyPatch(Map.of("maxTokens", 2048));

        assertEquals(2048, cfg.getMaxTokens());
        assertEquals(0.7,  cfg.getTemperature(), 0.001); // unchanged
    }

    @Test
    void applyPatch_withModelId_updatesModelId() {
        ChatUiConfig cfg = defaults();
        cfg.applyPatch(Map.of("modelId", "Qwen/Qwen3-Coder-480B-A35B-Instruct-FP8"));

        assertEquals("Qwen/Qwen3-Coder-480B-A35B-Instruct-FP8", cfg.getModelId());
        assertEquals(0.7, cfg.getTemperature(), 0.001); // unchanged
    }

    @Test
    void applyPatch_withMultipleFields_updatesAll() {
        ChatUiConfig cfg = defaults();
        cfg.applyPatch(Map.of("temperature", 0.0, "maxTokens", 8192, "modelId", "some-model"));

        assertEquals(0.0,         cfg.getTemperature(), 0.001);
        assertEquals(8192,        cfg.getMaxTokens());
        assertEquals("some-model", cfg.getModelId());
    }
}
