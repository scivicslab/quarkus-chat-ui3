package com.scivicslab.chatui3.turingwf;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter; // stored in RunState for requestStop()
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Lists, parses, and runs external Turing Workflow YAML files from the user's workflow directory.
 * Execution uses the same embedded Turing engine as WorkflowDispatcher. Output is accumulated in
 * memory and served to the frontend via polling (POST /run → GET /status/{runId}).
 */
@ApplicationScoped
public class TuringWorkflowRunner {

    private static final Logger LOG = Logger.getLogger(TuringWorkflowRunner.class.getName());
    static final int DEFAULT_MAX_ITERATIONS = 1_000_000;

    @ConfigProperty(name = "chatui3.workflow.dir", defaultValue = "${user.home}/works/workflow")
    String workflowDirRaw;

    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    private Path workflowDir() {
        String s = workflowDirRaw.replace("${user.home}", System.getProperty("user.home", "~"));
        return Paths.get(s);
    }

    /** Lists all .yaml files in the configured workflow directory (names without extension). */
    public List<String> listWorkflows() {
        Path dir = workflowDir();
        if (!Files.isDirectory(dir)) return List.of();
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.yaml$", ""))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list workflows in " + dir, e);
            return List.of();
        }
    }

    /** Reads and parses a workflow YAML, returning its metadata + declared params. */
    public WorkflowSpec getWorkflow(String name) {
        Path file = workflowDir().resolve(name + ".yaml");
        if (!Files.exists(file)) return null;
        try {
            String yaml = Files.readString(file);
            Yaml parser = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) parser.load(yaml);
            if (doc == null) return null;
            String description = Objects.toString(doc.get("description"), "").strip();
            List<ParamSpec> params = new ArrayList<>();
            Object paramsRaw = doc.get("params");
            if (paramsRaw instanceof Map<?, ?> paramsMap) {
                for (Map.Entry<?, ?> e : paramsMap.entrySet()) {
                    String pName = String.valueOf(e.getKey());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = e.getValue() instanceof Map
                            ? (Map<String, Object>) e.getValue() : Map.of();
                    String pDesc = Objects.toString(meta.get("description"), "");
                    Object defVal = meta.get("default");
                    String defStr = defVal != null ? String.valueOf(defVal) : null;
                    params.add(new ParamSpec(pName, pDesc, defStr, defStr == null));
                }
            }
            return new WorkflowSpec(name, description, params, yaml);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cannot parse workflow " + name, e);
            return null;
        }
    }

    /** Starts a workflow run in a virtual thread. Returns a runId the caller polls with. */
    public String startRun(String name, Map<String, String> inputParams, int maxIterations) {
        String runId = UUID.randomUUID().toString();
        RunState state = new RunState();
        runs.put(runId, state);
        Thread.ofVirtual().name("twf-" + name).start(() -> executeRun(name, inputParams, maxIterations, state));
        return runId;
    }

    /** Requests a running workflow to stop gracefully. No-op if the runId is unknown or already done. */
    public void stopRun(String runId) {
        RunState state = runs.get(runId);
        if (state != null && state.interpreter != null) {
            state.interpreter.requestStop();
        }
    }

    /** Returns new output lines since the last poll, plus done/error flags. Null if runId unknown. */
    public RunStatus pollStatus(String runId) {
        RunState state = runs.get(runId);
        if (state == null) return null;
        List<String> newLines;
        synchronized (state.pendingLines) {
            newLines = new ArrayList<>(state.pendingLines);
            state.pendingLines.clear();
        }
        return new RunStatus(newLines, state.done, state.error);
    }

    private void executeRun(String name, Map<String, String> inputParams, int maxIterations, RunState state) {
        Path file = workflowDir().resolve(name + ".yaml");
        IIActorSystem system = new IIActorSystem("twf-" + name);
        try {
            Interpreter interp = new Interpreter.Builder()
                    .loggerName("twf")
                    .team(system)
                    .build();
            interp.setWorkflowBaseDir(workflowDir().toString());
            state.interpreter = interp;

            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));

            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            mux.addTarget(new LineCollectingAccumulator(state));
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));

            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interp, system);
            interp.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);

            try (var in = Files.newInputStream(file)) {
                interp.readYaml(in);
            }
            for (Map.Entry<String, String> e : inputParams.entrySet()) {
                interpreterActor.callByActionName("putJson", new JSONObject()
                        .put("path", e.getKey())
                        .put("value", e.getValue())
                        .toString());
            }

            ActionResult result = interp.runUntilEnd(maxIterations);
            if (!result.isSuccess()) {
                state.addLine("[error] workflow did not complete: " + result.getResult());
                state.error = "workflow did not complete";
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Turing workflow run failed: " + name, e);
            state.addLine("[error] " + e.getMessage());
            state.error = e.getMessage();
        } finally {
            system.terminateIIActors();
            system.terminate();
            state.done = true;
        }
    }

    // ── Data types ───────────────────────────────────────────────────────────

    public record WorkflowSpec(String name, String description, List<ParamSpec> params, String yaml) {}

    public record ParamSpec(String name, String description, String defaultValue, boolean required) {}

    public record RunStatus(List<String> lines, boolean done, String error) {}

    static final class RunState {
        final List<String> pendingLines = new ArrayList<>();
        volatile boolean done = false;
        volatile String error = null;
        volatile Interpreter interpreter = null;

        synchronized void addLine(String line) { pendingLines.add(line); }
    }

    private static final class LineCollectingAccumulator implements Accumulator {
        private final RunState state;
        private final AtomicInteger count = new AtomicInteger();

        LineCollectingAccumulator(RunState s) { this.state = s; }

        @Override
        public void add(String source, String type, String data) {
            if (data != null && !data.isBlank()) {
                state.addLine("[" + source + "] " + data.stripTrailing());
            }
            count.incrementAndGet();
        }

        @Override public String getSummary() { return "collected " + count.get() + " entries"; }
        @Override public int getCount()      { return count.get(); }
        @Override public void clear()        { count.set(0); }
    }
}
