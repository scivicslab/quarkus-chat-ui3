package com.scivicslab.chatui3.actor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.rest.ChatEvent;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the SSE connection to the browser. ChatActor calls emit() to push events downstream.
 * One instance per session; held via ActorRef so all access is serialized.
 */
public class SseActor {

    private static final Logger LOG = Logger.getLogger(SseActor.class.getName());

    private final ObjectMapper mapper;
    private volatile UnicastProcessor<String> processor;

    public SseActor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Called by ChatResource when the browser opens /api/chat/stream. */
    public UnicastProcessor<String> openStream() {
        processor = UnicastProcessor.create();
        return processor;
    }

    /** Serializes event to JSON and pushes it to the SSE stream. */
    public void emit(ChatEvent event) {
        UnicastProcessor<String> p = processor;
        if (p == null) return;
        try {
            p.onNext(mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Failed to serialize ChatEvent", e);
        } catch (Exception e) {
            // Stream may have been cancelled by the client
            LOG.log(Level.FINE, "SSE emit skipped (stream closed): " + e.getMessage());
        }
    }

    public void complete() {
        UnicastProcessor<String> p = processor;
        if (p != null) p.onComplete();
    }
}
