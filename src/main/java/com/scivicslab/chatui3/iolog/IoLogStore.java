package com.scivicslab.chatui3.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.H2LogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionStatus;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the H2 store that persists the complete LLM I/O (s_iolog), and the conversation-scoped
 * session id. The agent loop builds a fresh actor system per turn, so the store and the session
 * must live here (application-scoped), not in the per-turn system.
 *
 * <p>One H2 "session" = one browser conversation: a session is opened on first use and reused across
 * turns; {@code resetSession()} (memory reset) ends it. The DB path is a startup property
 * ({@code chatui3.iolog.db-path}); when unset it defaults to a relative path, i.e. created in the
 * current working directory ({@code ./chatui3-iolog.mv.db}), mirroring actor-IaC's convention.</p>
 *
 * <p>Logging is best-effort: if the DB cannot be opened, the rest of the app keeps working and the
 * complete log is simply unavailable.</p>
 */
@ApplicationScoped
public class IoLogStore {

    private static final Logger LOG = Logger.getLogger(IoLogStore.class.getName());

    @ConfigProperty(name = "chatui3.iolog.db-path", defaultValue = "chatui3-iolog")
    String dbPath;

    private DistributedLogStore store;
    private ExecutorService dbExecutor;
    private long sessionId = -1;
    private boolean failed = false;

    private synchronized void ensureStore() {
        if (store != null || failed) {
            return;
        }
        try {
            store = new H2LogStore(Path.of(dbPath));
            dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "iolog-db");
                t.setDaemon(true);
                return t;
            });
            LOG.info("I/O log DB opened: " + Path.of(dbPath).toAbsolutePath() + ".mv.db");
        } catch (Exception e) {
            failed = true;
            LOG.log(Level.SEVERE, "Failed to open I/O log DB; complete logging disabled", e);
        }
    }

    /** Returns the current conversation's session id, opening a session on first use; -1 if unavailable. */
    public synchronized long ensureSession() {
        ensureStore();
        if (store == null) {
            return -1;
        }
        if (sessionId < 0) {
            try {
                sessionId = store.startSession("chatui3-conversation", 1);
                LOG.info("I/O log session started: " + sessionId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "startSession failed", e);
                return -1;
            }
        }
        return sessionId;
    }

    /** Ends the current conversation session (called on memory reset). */
    public synchronized void resetSession() {
        if (store != null && sessionId >= 0) {
            try {
                store.endSession(sessionId, SessionStatus.COMPLETED);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "endSession failed", e);
            }
        }
        sessionId = -1;
    }

    /** The shared store (read side for the browse view). May be null if logging is disabled. */
    public synchronized DistributedLogStore store() {
        ensureStore();
        return store;
    }

    /** The single-threaded executor used to dispatch async DB writes. May be null if disabled. */
    public synchronized ExecutorService dbExecutor() {
        ensureStore();
        return dbExecutor;
    }

    /**
     * Writes one entry to the given session. Thread-safe: the store's single writer thread serializes
     * all writes, so any caller (any actor/thread) may call this directly. No-op if logging is off or
     * sessionId is invalid.
     */
    public void record(long sessionId, String node, String label, String content) {
        if (sessionId < 0) {
            return;
        }
        DistributedLogStore s = store();
        if (s == null) {
            return;
        }
        try {
            s.logAction(sessionId, node, label, "output", 0, 0L, content);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "I/O log record failed", e);
        }
    }

    /** Opens a new session with the given workflow name (e.g. entry A). Returns -1 if unavailable. */
    public synchronized long startNamedSession(String workflowName) {
        ensureStore();
        if (store == null) {
            return -1;
        }
        try {
            return store.startSession(workflowName, 1);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "startSession failed", e);
            return -1;
        }
    }

    /** Ends a specific session (COMPLETED). */
    public void endNamedSession(long sessionId) {
        if (sessionId < 0) {
            return;
        }
        DistributedLogStore s = store();
        if (s == null) {
            return;
        }
        try {
            s.endSession(sessionId, SessionStatus.COMPLETED);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "endSession failed", e);
        }
    }

    @PreDestroy
    void shutdown() {
        resetSession();
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                // shutting down; ignore
            }
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }
    }
}
