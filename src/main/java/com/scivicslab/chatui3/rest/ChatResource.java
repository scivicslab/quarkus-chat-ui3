package com.scivicslab.chatui3.rest;

import com.scivicslab.chatui3.actor.ActorNode;
import com.scivicslab.chatui3.actor.ChatActorSystem;
import com.scivicslab.chatui3.agent.AgentLoopRunner;
import com.scivicslab.chatui3.agent.WorkflowCatalog;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.context.ConversationStore;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.iolog.IoLogView;
import com.scivicslab.chatui3.logging.LogTap;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestSseElementType;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Path("/api")
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class.getName());

    @Inject
    ChatActorSystem system;

    @Inject
    LogTap logTap;

    @Inject
    AgentLoopRunner agentLoopRunner;

    @Inject
    ConversationStore conversation;

    @Inject
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

    @Inject
    WorkflowCatalog workflowCatalog;

    // ── SSE stream ────────────────────────────────────────────────────────────

    @GET
    @Path("/chat/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestSseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        return system.openSseStream();
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response chat(Map<String, Object> body) {
        // Two entries reach this endpoint, distinguished by request shape:
        //   - browser UI (chat-ui app.js) sends {text, ...} -> run the in-process agent loop;
        //   - entry A (Turing Workflow / ChatUi3Actor) sends {message} -> single-shot LLM primitive.
        // Map<String,Object> so the browser's boolean fields (e.g. noThink) deserialize cleanly.
        Object textVal    = body != null ? body.get("text") : null;
        Object messageVal = body != null ? body.get("message") : null;
        Object sourceVal  = body != null ? body.get("source") : null;
        String text    = textVal != null ? String.valueOf(textVal) : null;
        String message = messageVal != null ? String.valueOf(messageVal) : null;
        // Who entered this prompt: the browser UI sends source="browser"; any other client (e.g. a
        // direct API/curl call) typically omits it, so default to "api" to mark non-UI input.
        String source  = (sourceVal != null && !String.valueOf(sourceVal).isBlank())
                ? String.valueOf(sourceVal) : "api";

        // Always return a JSON body: the browser does `await response.json()`, so an empty
        // body throws "Unexpected end of JSON input". The turn's content arrives via SSE; this
        // response only acknowledges acceptance.
        if (text != null && !text.isBlank()) {
            agentLoopRunner.launch(text, source);
            return Response.ok(Map.of("type", "accepted")).build();
        }
        if (message != null && !message.isBlank()) {
            system.getChatActorRef().tell(a -> a.startTurn(message));
            return Response.ok(Map.of("type", "accepted")).build();
        }
        return Response.status(400).entity(Map.of("type", "error",
                "content", "text (browser) or message (workflow) field required")).build();
    }

    // ── Cancel ──────────────────────────────────────────────────────────────────

    @POST
    @Path("/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancel() {
        agentLoopRunner.cancelCurrent();
        return Response.ok(Map.of("type", "cancelled")).build();
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig() {
        try {
            ChatUiConfig cfg = system.getChatActorRef().ask(a -> a.getConfig()).get();
            Map<String, Object> out = Map.of(
                "vllmBaseUrl",  cfg.getVllmBaseUrl(),
                "modelId",      cfg.getModelId() == null ? "" : cfg.getModelId(),
                "temperature",  cfg.getTemperature(),
                "maxTokens",    cfg.getMaxTokens()
            );
            return Response.ok(out).build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return Response.status(400).entity("patch body required").build();
        }
        system.getChatActorRef().tell(a -> a.applyConfigPatch(patch));
        return Response.ok().build();
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * The conversation as the model actually has it (server-authoritative context), so the chat pane
     * can render exactly what is in context — including turns entered by non-UI clients. Each turn
     * carries its number and the prompt's source.
     */
    @GET
    @Path("/conversation")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ConversationStore.Turn> conversation() {
        return conversation.conversation();
    }

    @DELETE
    @Path("/history")
    public Response clearHistory() {
        system.getChatActorRef().tell(a -> a.clearHistory());   // entry A (single-shot) history
        conversation.clear();                                    // entry B (browser) conversation memory
        ioLog.resetSession();                                     // end the I/O log session = start a new conversation
        return Response.ok().build();
    }

    // ── Models ────────────────────────────────────────────────────────────────

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listModels() {
        try {
            ChatUiConfig cfg = system.getChatActorRef().ask(a -> a.getConfig()).get();
            String server = cfg.getVllmBaseUrl();
            List<String> models = system.getChatActorRef().ask(a -> a.listModels()).get();
            // The browser UI (chat-ui app.js) expects an array of model objects:
            // [{name, type, server}]. type=local marks vLLM-served models, which the UI
            // sends per-request. The server field lets the UI group models by endpoint.
            List<Map<String, Object>> out = models.stream()
                    .map(id -> Map.<String, Object>of("name", id, "type", "local", "server", server))
                    .toList();
            return Response.ok(out).build();
        } catch (Exception e) {
            return Response.status(503).entity("vLLM unavailable: " + e.getMessage()).build();
        }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logs() {
        return Response.ok(logTap.recent(500)).build();
    }

    // ── Workflows (right-pane Workflow tab): read-only system workflow YAML ──────

    /** Lists the system workflows the editor can display. */
    @GET
    @Path("/workflows")
    @Produces(MediaType.APPLICATION_JSON)
    public List<WorkflowCatalog.WorkflowInfo> workflows() {
        return workflowCatalog.list();
    }

    /** Returns one system workflow's read-only YAML. */
    @GET
    @Path("/workflows/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response workflowYaml(@PathParam("name") String name) {
        String yaml = workflowCatalog.systemYaml(name);
        if (yaml == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "unknown workflow: " + name)).build();
        }
        return Response.ok(Map.of("name", name, "yaml", yaml, "editable", false)).build();
    }

    // ── Actors ────────────────────────────────────────────────────────────────

    /** Live actor tree: chatui3 root -> persistent actors, plus the running agent loop (if any). */
    @GET
    @Path("/actors")
    @Produces(MediaType.APPLICATION_JSON)
    public ActorNode actors() {
        return system.getActorTree(agentLoopRunner.getActiveAgentTree());
    }

    // ── Complete I/O log (s_iolog): persistent H2 sessions, for the browse/compare view ─────────

    /** Lists conversation sessions (most recent first) from the complete I/O log. */
    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessions() {
        DistributedLogStore store = ioLog.store();
        if (store == null) {
            return Response.ok(List.of()).build();
        }
        List<Map<String, Object>> out = store.listSessions(200).stream()
                .map(this::sessionToMap)
                .toList();
        return Response.ok(out).build();
    }

    /** Lists the agents (actors) that logged in a session, with line counts (agent-axis index). */
    @GET
    @Path("/sessions/{id}/agents")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IoLogView.AgentInfo> sessionAgents(@PathParam("id") long id) {
        return ioLogView.agents(id);
    }

    /** Deletes one log session and all its logs/node_results. Refuses the active conversation session. */
    @DELETE
    @Path("/sessions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSession(@PathParam("id") long id) {
        int n = ioLog.deleteSession(id);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n)).build();
    }

    /** Bulk-deletes sessions started more than {@code days} days ago (active session excluded). */
    @DELETE
    @Path("/sessions/old")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteOldSessions(@QueryParam("days") @DefaultValue("30") int days) {
        int n = ioLog.deleteSessionsOlderThan(days);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n, "olderThanDays", days)).build();
    }

    /**
     * Returns a shaped, filtered, bounded page of one session's I/O. Filters (any may be omitted):
     * {@code agent} (node_id), {@code label} (turn/step/llm|tool substring), {@code level}
     * (DEBUG/INFO/WARN/ERROR threshold), {@code q} (message text), {@code since}/{@code until}
     * (ISO LocalDateTime), {@code limit}. {@code message} is a preview; full text via the entry endpoint.
     */
    @GET
    @Path("/sessions/{id}/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public IoLogView.Page sessionLogs(@PathParam("id") long id,
            @QueryParam("agent") String agent,
            @QueryParam("label") String label,
            @QueryParam("level") String level,
            @QueryParam("q") String q,
            @QueryParam("since") String since,
            @QueryParam("until") String until,
            @QueryParam("limit") @DefaultValue("0") int limit) {
        return ioLogView.logs(id, new IoLogView.Filter(agent, label, level, q, since, until, limit));
    }

    /** Returns the full (untruncated) message of one log entry, for on-expand lazy loading. */
    @GET
    @Path("/sessions/{id}/entry/{logId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessionEntry(@PathParam("id") long id, @PathParam("logId") long logId) {
        return Response.ok(Map.of("message", ioLogView.fullMessage(id, logId))).build();
    }

    /**
     * Returns the reconstructed agent-loop trace for a session: per turn, the ordered flow of LLM
     * steps (model text + tool calls) and tool executions (name/input + observation digest). This is
     * the loop's play-by-play, shaped server-side from the complete I/O log.
     */
    @GET
    @Path("/sessions/{id}/trace")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IoLogView.TraceTurn> sessionTrace(@PathParam("id") long id) {
        return ioLogView.trace(id);
    }

    private Map<String, Object> sessionToMap(SessionSummary s) {
        // String.valueOf guards nulls (endedAt/status may be null): Map.of rejects null values.
        return Map.of(
                "sessionId",       s.getSessionId(),
                "workflowName",    String.valueOf(s.getWorkflowName()),
                "startedAt",       String.valueOf(s.getStartedAt()),
                "endedAt",         String.valueOf(s.getEndedAt()),
                "status",          String.valueOf(s.getStatus()),
                "totalLogEntries", s.getTotalLogEntries());
    }

}
