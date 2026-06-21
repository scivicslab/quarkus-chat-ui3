package com.scivicslab.chatui3.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Logs a vLLM streaming response in time windows instead of dumping each split SSE chunk.
 *
 * <p>The raw response arrives as many small {@code data: {...}} SSE chunks. Logging them one by
 * one (or as one giant blob) is hard to read. This batcher accumulates the response text and, on a
 * periodic tick (~every 5 seconds), emits one log line with the window's text as JSON. A window in
 * which nothing arrived is skipped (nothing is logged).</p>
 *
 * <p>It is driven through a POJO-actor {@code ActorRef}, so {@link #append} (called on the
 * stream-reading thread) and {@link #tick}/{@link #end} (called on the timer thread) are serialized
 * by the actor's mailbox — no explicit locks, no torn reads of the buffer.</p>
 *
 * <p>Assumes one active stream at a time (the session runs one turn at a time); concurrent streams
 * would interleave into the same window.</p>
 */
public class SseBatchLogger {

    private static final Logger LOG = Logger.getLogger(SseBatchLogger.class.getName());

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();
    private boolean active;
    private int chunkCount;
    private int windowIndex;

    public SseBatchLogger(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Marks the start of a new streamed response. */
    public void begin() {
        this.active = true;
        this.text.setLength(0);
        this.chunkCount = 0;
        this.windowIndex = 0;
    }

    /** Adds one text fragment from the stream to the current window. */
    public void append(String fragment) {
        if (!active || fragment == null || fragment.isEmpty()) {
            return;
        }
        this.text.append(fragment);
        this.chunkCount++;
    }

    /** Periodic flush (called about every 5 seconds): logs the window only if text accumulated. */
    public void tick() {
        if (active && text.length() > 0) {
            flush("window");
        }
    }

    /** Final flush at stream end: logs any remaining text, then goes idle. */
    public void end() {
        if (active && text.length() > 0) {
            flush("final");
        }
        this.active = false;
    }

    private void flush(String label) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("window", windowIndex);
        obj.put("label", label);
        obj.put("chunks", chunkCount);
        obj.put("text", text.toString());
        String json;
        try {
            json = mapper.writeValueAsString(obj);
        } catch (Exception e) {
            json = "{\"window\":" + windowIndex + ",\"chunks\":" + chunkCount + "}";
        }
        LOG.info("vLLM response " + json);
        this.windowIndex++;
        this.text.setLength(0);
        this.chunkCount = 0;
    }
}
