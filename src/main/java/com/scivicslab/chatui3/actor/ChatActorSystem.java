package com.scivicslab.chatui3.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.llm.SseBatchLogger;
import com.scivicslab.chatui3.llm.VllmClient;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
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

    @ConfigProperty(name = "chatui3.vllm-base-url")
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
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<SseActor> sseActorRef;
    private ActorRef<SseBatchLogger> batchRef;
    private ScheduledExecutorService logScheduler;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("chatui3");

        // Batch logger for vLLM responses: accumulates streamed text and a 5s ticker flushes it
        // to the log as JSON (instead of dumping each split SSE chunk). Empty windows are skipped.
        batchRef = actorSystem.actorOf("sse-batch", new SseBatchLogger(objectMapper));
        logScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-batch-tick");
            t.setDaemon(true);
            return t;
        });
        logScheduler.scheduleAtFixedRate(() -> batchRef.tell(SseBatchLogger::tick), 5, 5, TimeUnit.SECONDS);

        ChatUiConfig config   = new ChatUiConfig(vllmBaseUrl, defaultModel.orElse(""), defaultTemperature, defaultMaxTokens);
        VllmClient vllmClient = new VllmClient(objectMapper, batchRef, ioLog);

        SseActor sseActor = new SseActor(objectMapper);
        sseActorRef = actorSystem.actorOf("sse", sseActor);

        ChatActor chatActor = new ChatActor(vllmClient, config, ioLog);
        chatActorRef = actorSystem.actorOf("chat", chatActor);
        chatActorRef.tell(a -> a.setSseRef(sseActorRef));
        chatActorRef.tell(a -> a.setSelfRef(chatActorRef));

        LOG.info("ChatActorSystem initialized (vllm=" + vllmBaseUrl + ")");
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

    public ActorRef<ChatActor> getChatActorRef() { return chatActorRef; }
    public ActorRef<SseActor>  getSseActorRef()  { return sseActorRef; }
    public ActorRef<SseBatchLogger> getSseBatchLoggerRef() { return batchRef; }
    public String getVllmBaseUrl()               { return vllmBaseUrl; }
}
