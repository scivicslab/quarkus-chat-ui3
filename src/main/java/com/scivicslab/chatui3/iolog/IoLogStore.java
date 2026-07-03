package com.scivicslab.chatui3.iolog;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.H2LogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionStatus;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * ({@code chatui3.iolog.db-path}, default {@code chatui3-iolog}) with the instance's HTTP port
 * appended, so each instance opens its own file (e.g. {@code ./chatui3-iolog-18090.mv.db}). The port
 * suffix keeps two instances from sharing one H2 store via {@code AUTO_SERVER}.</p>
 *
 * <p>Logging is best-effort: if the DB cannot be opened, the rest of the app keeps working and the
 * complete log is simply unavailable.</p>
 */
@ApplicationScoped
public class IoLogStore {

    private static final Logger LOG = Logger.getLogger(IoLogStore.class.getName());

    @ConfigProperty(name = "chatui3.iolog.db-path", defaultValue = "chatui3-iolog")
    String dbPath;

    // The instance's HTTP port, appended to the DB path so two chat-ui3 instances on different ports
    // never open the same H2 file. H2 opens with AUTO_SERVER=TRUE, so without this a second process
    // would connect into the first instance's database and the two would write to one shared store.
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    // Lossless MVStore compression for the complete-I/O log: the logged content is highly repetitive
    // (the full request JSON is re-serialized every step), so compression shrinks the .mv.db file
    // several-fold with no loss. Applies to newly written data.
    @ConfigProperty(name = "chatui3.iolog.compress", defaultValue = "true")
    boolean compress;

    /** The configured db-path with the instance's HTTP port appended (per-instance DB isolation). */
    private String dbPathForPort() {
        return dbPath + "-" + httpPort;
    }

    private DistributedLogStore store;
    private ExecutorService dbExecutor;
    private long sessionId = -1;
    private boolean failed = false;

    // Dedicated connection to the SAME per-port DB file for the browser conversation state
    // (system prompt + committed turns + the log session id to resume). IoLogStore owns ALL H2 access
    // for this instance so conversation_state and the sessions/logs tables live in one file — otherwise
    // a resumed session id would be foreign to this instance's log DB and every log write would fail the
    // sessions foreign key. Opened lazily in ensureStore().
    private Connection convDb;

    /** The browser conversation's persisted state. {@code sessionId} is the log session to resume. */
    public record ConversationState(long sessionId, String systemPrompt, String turnsJson) {}

    /**
     * The actor wrapping {@link #store}, created by ChatActorSystem as a child of root. When set,
     * {@link #record} dispatches writes through it (fire-and-forget tell) so every complete-I/O write
     * funnels through one single-writer actor that is visible in the actor tree (GET /api/actors),
     * mirroring actor-IaC's "the log store is an actor (3-layer)" pattern.
     */
    private volatile ActorRef<DistributedLogStore> logStoreRef;

    private synchronized void ensureStore() {
        if (store != null || failed) {
            return;
        }
        try {
            store = new H2LogStore(Path.of(dbPathForPort()), compress);
            dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "iolog-db");
                t.setDaemon(true);
                return t;
            });
            LOG.info("I/O log DB opened: " + Path.of(dbPathForPort()).toAbsolutePath() + ".mv.db"
                    + " (compress=" + compress + ")");
            openConvDb();
        } catch (Exception e) {
            failed = true;
            LOG.log(Level.SEVERE, "Failed to open I/O log DB; complete logging disabled", e);
        }
    }

    // ── Conversation state (owned here so it shares the ONE per-port DB file) ──────────────────

    /** Opens the conversation_state connection on the same DB file and ensures its table exists. */
    private void openConvDb() {
        try {
            String url = "jdbc:h2:" + Path.of(dbPathForPort()).toAbsolutePath() + ";AUTO_SERVER=TRUE"
                    + (compress ? ";COMPRESS=TRUE" : "");
            convDb = DriverManager.getConnection(url);
            try (var st = convDb.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS conversation_state ("
                        + "conv_key VARCHAR(64) PRIMARY KEY, session_id BIGINT, "
                        + "system_prompt CLOB, turns_json CLOB, updated_at TIMESTAMP)");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "conversation_state persistence unavailable", e);
            convDb = null;
        }
    }

    /** Loads the persisted conversation state for {@code key}, or null if none/unavailable. */
    public synchronized ConversationState loadConversationState(String key) {
        ensureStore();
        if (convDb == null) {
            return null;
        }
        try (PreparedStatement ps = convDb.prepareStatement(
                "SELECT session_id, system_prompt, turns_json FROM conversation_state WHERE conv_key=?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ConversationState(rs.getLong("session_id"),
                        rs.getString("system_prompt"), rs.getString("turns_json"));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load conversation state", e);
            return null;
        }
    }

    /** Upserts the single conversation_state row for {@code key}. No-op if persistence is unavailable. */
    public synchronized void saveConversationState(
            String key, long sessionId, String systemPrompt, String turnsJson) {
        ensureStore();
        if (convDb == null) {
            return;
        }
        try (PreparedStatement ps = convDb.prepareStatement(
                "MERGE INTO conversation_state (conv_key, session_id, system_prompt, turns_json, updated_at) "
                        + "VALUES (?,?,?,?,CURRENT_TIMESTAMP)")) {
            ps.setString(1, key);
            ps.setLong(2, sessionId);
            ps.setString(3, systemPrompt);
            ps.setString(4, turnsJson);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to persist conversation state", e);
        }
    }

    /** Deletes the conversation_state row for {@code key} (memory reset). */
    public synchronized void clearConversationState(String key) {
        if (convDb == null) {
            return;
        }
        try (PreparedStatement ps = convDb.prepareStatement(
                "DELETE FROM conversation_state WHERE conv_key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to clear persisted conversation", e);
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

    /** The current conversation's log session id, or -1 if none. */
    public synchronized long currentSessionId() {
        return sessionId;
    }

    /**
     * Resumes an existing conversation log session (e.g. after a restart, from persisted state) so
     * the conversation keeps appending to the same session instead of starting a new one.
     */
    public synchronized void resumeSession(long sid) {
        ensureStore();
        if (store != null && sid >= 0) {
            sessionId = sid;
            LOG.info("Resumed conversation log session: " + sid);
        }
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

    /**
     * Attaches the actor (a child of the chatui3 root) that wraps {@link #store}. After this,
     * {@link #record} writes via the actor instead of calling the store directly, so all writes
     * funnel through one single-writer actor that shows up in the actor tree.
     */
    public void attachActor(ActorRef<DistributedLogStore> ref) {
        this.logStoreRef = ref;
    }

    /** The single-writer logStore actor (child of root), or null if logging is disabled/not attached.
     *  Used to wire a {@code DatabaseAccumulator} into the agent-loop's outputMultiplexer. */
    public ActorRef<DistributedLogStore> logStoreActor() {
        return logStoreRef;
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
        ActorRef<DistributedLogStore> ref = logStoreRef;
        if (ref != null) {
            // Fire-and-forget through the single-writer logStore actor (actor-IaC pattern). The
            // store's own writer thread still batches the actual H2 write, so this returns at once.
            ref.tell(s -> s.logAction(sessionId, node, label, "output", 0, 0L, content));
            return;
        }
        // Fallback before the actor is attached (or if logging failed to open): write directly.
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

    // ---- Maintenance: prune old / unwanted log data ------------------------
    //
    // Deletes run over a short-lived, separate JDBC connection (H2 AUTO_SERVER mode allows multiple
    // connections to the same DB), so they never touch H2LogStore's read/write connections. The active
    // conversation session is always excluded, and only non-active sessions are removed, so a delete
    // never contends with the rows currently being written.

    /** The same JDBC URL H2LogStore uses, for a short-lived maintenance connection. */
    private String jdbcUrl() {
        return "jdbc:h2:" + Path.of(dbPathForPort()).toAbsolutePath() + ";AUTO_SERVER=TRUE"
                + (compress ? ";COMPRESS=TRUE" : "");
    }

    /**
     * Deletes one session and all of its logs / node_results. Refuses to delete the active
     * conversation session. Returns the number of sessions deleted (1 or 0), or -1 on error.
     */
    public synchronized int deleteSession(long id) {
        if (id < 0) return 0;
        if (id == sessionId) {
            LOG.warning("Refusing to delete the active conversation session " + id);
            return 0;
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
            c.setAutoCommit(false);
            try {
                update(c, "DELETE FROM logs WHERE session_id = ?", id);
                update(c, "DELETE FROM node_results WHERE session_id = ?", id);
                int n = update(c, "DELETE FROM sessions WHERE id = ?", id);
                c.commit();
                LOG.info("Deleted log session " + id + " (" + n + " row)");
                return n;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "deleteSession failed for " + id, e);
            return -1;
        }
    }

    /**
     * Deletes every session started more than {@code days} days ago (and its logs / node_results),
     * excluding the active conversation session. Returns the number of sessions deleted, or -1 on error.
     */
    public synchronized int deleteSessionsOlderThan(int days) {
        if (days < 0) return 0;
        String pred = "started_at < DATEADD('DAY', ?, CURRENT_TIMESTAMP) AND id <> ?";
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
            c.setAutoCommit(false);
            try {
                update(c, "DELETE FROM logs WHERE session_id IN (SELECT id FROM sessions WHERE " + pred + ")",
                        -days, sessionId);
                update(c, "DELETE FROM node_results WHERE session_id IN (SELECT id FROM sessions WHERE " + pred + ")",
                        -days, sessionId);
                int n = update(c, "DELETE FROM sessions WHERE " + pred, -days, sessionId);
                c.commit();
                LOG.info("Deleted " + n + " session(s) older than " + days + " day(s)");
                return n;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "deleteSessionsOlderThan failed", e);
            return -1;
        }
    }

    /** Runs one parameterized update/delete and returns the affected-row count. */
    private static int update(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    @PreDestroy
    void shutdown() {
        // Do NOT end the conversation session here: leaving it open (and persisting it, see
        // ConversationStore) lets the conversation resume across a restart. Only an explicit
        // "New conversation" (resetSession via DELETE /api/history) ends it.
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                // shutting down; ignore
            }
        }
        if (convDb != null) {
            try {
                convDb.close();
            } catch (Exception e) {
                // shutting down; ignore
            }
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }
    }
}
