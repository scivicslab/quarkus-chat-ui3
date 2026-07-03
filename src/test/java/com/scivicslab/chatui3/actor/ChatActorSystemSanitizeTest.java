package com.scivicslab.chatui3.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ChatActorSystem.sanitizeBaseUrl — unusable values fall back to the default")
class ChatActorSystemSanitizeTest {

    private static final String DEF = ChatActorSystem.DEFAULT_VLLM_BASE_URL;

    @Test void good_value_kept() {
        assertEquals("http://192.0.2.10:8000",
                ChatActorSystem.sanitizeBaseUrl("http://192.0.2.10:8000"));
    }

    @Test void trimmed() {
        assertEquals("http://192.0.2.10:8000",
                ChatActorSystem.sanitizeBaseUrl("  http://192.0.2.10:8000  "));
    }

    @Test void null_value_falls_back() {
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl(null));
    }

    @Test void blank_falls_back() {
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl(""));
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl("   "));
    }

    @Test void literal_null_string_falls_back() {
        // The bug: a launcher passed the literal string "null" → http://null:8000.
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl("null"));
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl("NULL"));
        assertEquals(DEF, ChatActorSystem.sanitizeBaseUrl(" null "));
    }
}
