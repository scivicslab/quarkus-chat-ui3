package com.scivicslab.chatui3.logging;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Keeps the most recent server log records in memory so the browser can show them
 * in the right-pane "Logs" tab via {@code GET /api/logs}. A {@link Handler} is attached
 * to the root logger at startup, and each record is stored in a bounded ring buffer.
 */
@ApplicationScoped
public class LogTap {

    /** One captured log line. {@code levelValue} is the numeric severity ({@code Level.intValue()}),
     *  which lets the UI filter by threshold regardless of the level's name (JUL "WARNING" and
     *  JBoss "WARN" are both 900). */
    public record Entry(long time, String level, int levelValue, String logger, String message) {}

    private static final int MAX = 1000;

    private final Deque<Entry> buffer = new ArrayDeque<>(MAX);
    private final Formatter formatter = new SimpleFormatter();

    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record == null) {
                return;
            }
            String loggerName = record.getLoggerName() == null ? "" : record.getLoggerName();
            int dot = loggerName.lastIndexOf('.');
            String shortLogger = dot >= 0 ? loggerName.substring(dot + 1) : loggerName;
            String message;
            try {
                message = formatter.formatMessage(record);
            } catch (Exception e) {
                message = String.valueOf(record.getMessage());
            }
            Entry entry = new Entry(record.getMillis(), record.getLevel().getName(),
                    record.getLevel().intValue(), shortLogger, message);
            synchronized (buffer) {
                buffer.addLast(entry);
                while (buffer.size() > MAX) {
                    buffer.pollFirst();
                }
            }
        }

        @Override
        public void flush() {
            // Nothing buffered downstream.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    void onStart(@Observes StartupEvent event) {
        java.util.logging.Logger.getLogger("").addHandler(handler);
    }

    /** Returns up to {@code limit} of the most recent entries, oldest first. */
    public List<Entry> recent(int limit) {
        synchronized (buffer) {
            int size = buffer.size();
            int skip = (limit > 0 && size > limit) ? size - limit : 0;
            List<Entry> out = new ArrayList<>(skip < size ? size - skip : 0);
            int i = 0;
            for (Entry entry : buffer) {
                if (i++ < skip) {
                    continue;
                }
                out.add(entry);
            }
            return out;
        }
    }
}
