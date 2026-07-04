package com.scivicslab.chatui3.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.llm.SseBatchLogger;
import com.scivicslab.chatui3.llm.VllmClient;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * CDI bean that owns the POJO-actor system. Creates ChatActor and SseActor at startup.
 */
@ApplicationScoped
public class ChatActorSystem {

    private static final Logger LOG = Logger.getLogger(ChatActorSystem.class.getName());

    /**
     * Last-resort fallback when the injected vLLM base URL is missing or unusable (blank / literal
     * "null"). Neutral placeholder; the real endpoint is supplied via config/env or the UI.
     */
    static final String DEFAULT_VLLM_BASE_URL = "http://vllm:8000";

    @ConfigProperty(name = "chatui3.vllm-base-url", defaultValue = DEFAULT_VLLM_BASE_URL)
    String vllmBaseUrl;

    @ConfigProperty(name = "chatui3.model")
    Optional<String> defaultModel;

    @ConfigProperty(name = "chatui3.temperature", defaultValue = "0.7")
    double defaultTemperature;

    @ConfigProperty(name = "chatui3.max-tokens", defaultValue = "4096")
    int defaultMaxTokens;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    IoLogStore ioLog;

    private ActorSystem actorSystem;
    private ActorRef<RootActor> rootRef;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<SseActor> sseActorRef;
    private ActorRef<SseBatchLogger> batchRef;
    private ScheduledExecutorService logScheduler;

    /**
     * Forces this bean to initialize at application startup (an {@code @ApplicationScoped} bean is
     * otherwise created lazily on first use). This guarantees the vLLM endpoint is logged immediately
     * in the System Log at boot — so the operator sees the effective configuration without having to
     * send a first request.
     */
    void onStart(@Observes StartupEvent event) {
        // Body intentionally empty: observing StartupEvent triggers @PostConstruct init() eagerly.
    }

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("chatui3");

        // Root actor: parents every persistent actor so the set forms a single tree from one root
        // (inspectable via GET /api/actors). createChild registers each child in the same system
        // and records the parent/child link; the returned refs are used exactly as before.
        rootRef = actorSystem.actorOf("root", new RootActor());

        // Log store as a single-writer actor under root (actor-IaC's "the log store is an actor"
        // pattern): the H2 store (s_iolog complete I/O) becomes a child of root so every write
        // funnels through one actor that is visible in the actor tree (GET /api/actors). Opens the
        // DB eagerly; if it fails to open, logging stays best-effort off and no actor is created.
        DistributedLogStore logStore = ioLog.store();
        if (logStore != null) {
            ActorRef<DistributedLogStore> logStoreRef = rootRef.createChild("logStore", logStore);
            ioLog.attachActor(logStoreRef);
        }

        // Batch logger for vLLM responses: accumulates streamed text and a 5s ticker flushes it
        // to the log as JSON (instead of dumping each split SSE chunk). Empty windows are skipped.
        batchRef = rootRef.createChild("sse-batch", new SseBatchLogger(objectMapper));
        logScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-batch-tick");
            t.setDaemon(true);
            return t;
        });
        logScheduler.scheduleAtFixedRate(() -> batchRef.tell(SseBatchLogger::tick), 5, 5, TimeUnit.SECONDS);

        // Guard against a launcher passing an empty or literal "null" value (e.g. AI-workspace expanding
        // an unset ${VLLM_ENDPOINT}, or a stale UI config): such a value would otherwise become a bogus
        // http://null:8000 URL and every request would ConnectException. Fall back to the default.
        // Log the RAW injected value and the effective value so the System Log shows exactly what the
        // launcher passed — including when a bad value triggered the fallback.
        String baseUrl = sanitizeBaseUrl(vllmBaseUrl);
        if (!baseUrl.equals(vllmBaseUrl)) {
            LOG.warning("chatui3.vllm-base-url injected as [" + vllmBaseUrl + "] is unusable"
                    + " (blank/null); falling back to default [" + baseUrl + "]."
                    + " Check the launcher: AI-workspace passes -Dchatui3.vllm-base-url from the"
                    + " 'vLLM Endpoint' form field (param key 'servers'); an unset ${VLLM_ENDPOINT}"
                    + " or a stale UI value produces this.");
        } else {
            LOG.info("chatui3.vllm-base-url = [" + baseUrl + "] (as injected)");
        }
        ChatUiConfig config   = new ChatUiConfig(baseUrl, defaultModel.orElse(""), defaultTemperature, defaultMaxTokens);
        VllmClient vllmClient = new VllmClient(objectMapper, batchRef, ioLog);

        SseActor sseActor = new SseActor(objectMapper);
        sseActorRef = rootRef.createChild("sse", sseActor);

        ChatActor chatActor = new ChatActor(vllmClient, config, ioLog);
        chatActorRef = rootRef.createChild("chat", chatActor);
        chatActorRef.tell(a -> a.setSseRef(sseActorRef));
        chatActorRef.tell(a -> a.setSelfRef(chatActorRef));

        LOG.info("ChatActorSystem initialized (vllm=" + baseUrl + ")");
    }

    /**
     * Returns a usable vLLM base URL: the configured value when present, otherwise
     * {@link #DEFAULT_VLLM_BASE_URL}. A blank value or the literal string {@code "null"}
     * (which launchers produce from an unset env var or a stale UI config) is treated as unset.
     */
    static String sanitizeBaseUrl(String value) {
        if (value == null) return DEFAULT_VLLM_BASE_URL;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("null")) return DEFAULT_VLLM_BASE_URL;
        return v;
    }

    @PreDestroy
    void shutdown() {
        if (logScheduler != null) logScheduler.shutdownNow();
        if (actorSystem != null) actorSystem.terminate();
    }

    /**
     * Opens a new SSE stream. Returns a Multi<String> that emits JSON ChatEvent strings.
     * Called by ChatResource when the browser connects to GET /api/chat/stream.
     */
    public Multi<String> openSseStream() {
        try {
            UnicastProcessor<String> processor =
                sseActorRef.ask(SseActor::openStream).get(5, TimeUnit.SECONDS);
            return processor;
        } catch (Exception e) {
            LOG.warning("Failed to open SSE stream: " + e.getMessage());
            return Multi.createFrom().empty();
        }
    }

    /**
     * Builds the actor tree for {@code GET /api/actors}: the persistent chatui3 tree
     * (root -&gt; sse-batch, sse, chat). When an agent loop is running, its subtree (from the
     * separate per-request IIActorSystem) is appended under root as an extra child, so the live
     * picture shows as one tree.
     */
    public ActorNode getActorTree(ActorNode agentSubtree) {
        ActorNode root = buildNode(rootRef);
        if (agentSubtree == null) return root;
        List<ActorNode> children = new ArrayList<>(root.children());
        children.add(agentSubtree);
        return new ActorNode(root.name(), root.type(), root.alive(), children);
    }

    // Walks one actor and its descendants. Uses only non-queue calls (isAlive / getNamesOfChildren)
    // plus askNow for the wrapped type, so it never blocks behind a busy actor's mailbox.
    private ActorNode buildNode(ActorRef<?> ref) {
        String type;
        try {
            @SuppressWarnings("unchecked")
            ActorRef<Object> r = (ActorRef<Object>) ref;
            type = r.askNow(o -> o.getClass().getSimpleName()).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            type = "?";
        }
        List<ActorNode> children = new ArrayList<>();
        for (String childName : new TreeSet<>(ref.getNamesOfChildren())) {
            ActorRef<Object> child = actorSystem.getActor(childName);
            if (child != null) children.add(buildNode(child));
        }
        return new ActorNode(ref.getName(), type, ref.isAlive(), children);
    }

    public ActorRef<ChatActor> getChatActorRef() { return chatActorRef; }
    public ActorRef<SseActor>  getSseActorRef()  { return sseActorRef; }
    public ActorRef<SseBatchLogger> getSseBatchLoggerRef() { return batchRef; }
    public String getVllmBaseUrl()               { return vllmBaseUrl; }
}
