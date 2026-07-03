package com.scivicslab.chatui3.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("VllmClient.normalizeBaseUrl — scheme and port normalization")
class VllmClientNormalizeTest {

    @Test void null_passthrough() {
        assertNull(VllmClient.normalizeBaseUrl(null));
    }

    @Test void blank_passthrough() {
        assertEquals("", VllmClient.normalizeBaseUrl(""));
        assertEquals("  ", VllmClient.normalizeBaseUrl("  "));
    }

    @Test void full_url_unchanged() {
        assertEquals("http://192.0.2.10:8000",
                VllmClient.normalizeBaseUrl("http://192.0.2.10:8000"));
        assertEquals("https://api.example.com:443",
                VllmClient.normalizeBaseUrl("https://api.example.com:443"));
    }

    @Test void scheme_added_when_missing_with_port() {
        // User typed "192.0.2.10:8000" — scheme must be prepended.
        assertEquals("http://192.0.2.10:8000",
                VllmClient.normalizeBaseUrl("192.0.2.10:8000"));
    }

    @Test void default_port_added_when_bare_host_no_scheme() {
        // User typed "192.0.2.10" — http:// AND :8000 must both be added.
        assertEquals("http://192.0.2.10:8000",
                VllmClient.normalizeBaseUrl("192.0.2.10"));
    }

    @Test void default_port_added_when_scheme_present_but_no_port() {
        // User typed "http://192.0.2.10" — only :8000 must be added.
        assertEquals("http://192.0.2.10:8000",
                VllmClient.normalizeBaseUrl("http://192.0.2.10"));
    }

    @Test void non_8000_port_preserved() {
        // If a non-default port is explicitly specified it must not be overwritten.
        assertEquals("http://192.0.2.10:9000",
                VllmClient.normalizeBaseUrl("192.0.2.10:9000"));
        assertEquals("http://192.0.2.10:9000",
                VllmClient.normalizeBaseUrl("http://192.0.2.10:9000"));
    }
}
