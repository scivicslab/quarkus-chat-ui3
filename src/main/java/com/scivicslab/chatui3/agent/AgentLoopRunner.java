package com.scivicslab.chatui3.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui3.actor.ChatActorSystem;
import com.scivicslab.chatui3.actor.SseActor;
import com.scivicslab.chatui3.config.ChatUiConfig;
import com.scivicslab.chatui3.context.ConversationStore;
import com.scivicslab.chatui3.iolog.IoLogStore;
import com.scivicslab.chatui3.llm.VllmClient;
import com.scivicslab.chatui3.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the in-process Turing Workflow agent loop for the browser (entry B).
 *
 * <p>When the browser sends a message, {@link #launch(String)} starts the {@code agent-react.yaml}
 * workflow on a virtual thread. The workflow drives the {@link AgentActor}, which runs the ReAct
 * think -&gt; act -&gt; observe loop (LLM calls, tool execution, conversation context) and streams
 * events back to the browser's {@link SseActor}. A stuck turn is stopped via
 * {@link AgentActor#cancel()}, called from the HTTP request thread ({@link #cancelCurrent()}) or on
 * teardown (AgentActor.close()).</p>
 */
@ApplicationScoped
public class AgentLoopRunner {

    private static final Logger LOG = Logger.getLogger(AgentLoopRunner.class.getName());
    private static final String WORKFLOW_RESOURCE = "/workflows/agent-react.yaml";
    private static final int MAX_ITERATIONS = 1000;

    @Inject
    ChatActorSystem chatSystem;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConversationStore conversation;

    @Inject
    IoLogStore ioLog;

    // Effective context-window limit (tokens) for budgeting requests (s_budget). Default conservative;
    // set to the model's real --max-model-len (e.g. 131072 for gemma-4) to trim only near the limit.
    @ConfigProperty(name = "chatui3.context-window", defaultValue = "32768")
    int contextWindow;

    private VllmClient vllmClient;

    /** The agent actor of the currently running loop, or null. Used by cancelCurrent(). */
    private volatile AgentActor currentAgent;

    @PostConstruct
    void init() {
        // Share the batch logger with the single-shot path so the browser agent loop's responses
        // are logged in the same windowed JSON form.
        this.vllmClient = new VllmClient(mapper, chatSystem.getSseBatchLoggerRef(), ioLog);
    }

    /** Starts the agent loop for one user message on a virtual thread (returns immediately). */
    public void launch(String userMessage) {
        Thread.ofVirtual().name("agent-loop").start(() -> run(userMessage));
    }

    /** Cancels the currently running agent loop (if any) by stopping its in-flight LLM turn. */
    public void cancelCurrent() {
        AgentActor agent = this.currentAgent;
        if (agent != null) {
            LOG.info("Cancelling current agent loop turn");
            agent.cancel();
        }
    }

    private void run(String userMessage) {
        ActorRef<SseActor> sseRef = chatSystem.getSseActorRef();
        ChatUiConfig config;
        try {
            config = chatSystem.getChatActorRef().ask(a -> a.getConfig()).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read config for agent loop", e);
            sseRef.tell(a -> a.emit(ChatEvent.error("agent loop config error: " + e.getMessage())));
            return;
        }

        IIActorSystem system = new IIActorSystem("agent-loop");
        try {
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("interpreter")
                    .team(system)
                    .build();
            interpreter.setWorkflowBaseDir(".");

            // Built-in actors (mirror the CLI runner so the engine has its standard helpers).
            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);

            // Complete I/O logging (s_iolog): one H2 session per conversation, reused across turns.
            // LLM calls are recorded at the shared VllmClient point; tool calls by the AgentActor —
            // both via IoLogStore, whose single writer serializes all writes.
            long sessionId = ioLog.ensureSession();

            // The agent actor drives the ReAct loop (think -> act -> observe). Its close() cancels
            // any in-flight LLM call on teardown; cancelCurrent() cancels it on user request.
            AgentActor agent = new AgentActor("agent", vllmClient, config, sseRef, conversation, system,
                    ioLog, sessionId, contextWindow);
            system.addIIActor(agent);
            this.currentAgent = agent;   // expose for cancelCurrent()

            // Make the user message available to the workflow as ${user.message}.
            interpreterActor.callByActionName("putJson", new JSONObject()
                    .put("path", "user.message")
                    .put("value", userMessage)
                    .toString());

            // Load the minimal agent-loop workflow from the classpath and run it.
            try (InputStream in = getClass().getResourceAsStream(WORKFLOW_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("workflow resource not found: " + WORKFLOW_RESOURCE);
                }
                interpreter.readYaml(in);
            }

            ActionResult result = interpreter.runUntilEnd(MAX_ITERATIONS);
            if (!result.isSuccess()) {
                sseRef.tell(a -> a.emit(ChatEvent.error("agent loop failed: " + result.getResult())));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Agent loop error", e);
            sseRef.tell(a -> a.emit(ChatEvent.error("agent loop error: " + e.getMessage())));
        } finally {
            this.currentAgent = null;
            // terminateIIActors() fires each actor's close() (AgentActor.close() cancels), then the pools.
            system.terminateIIActors();
            system.terminate();
        }
    }
}
