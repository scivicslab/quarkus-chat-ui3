package com.scivicslab.chatui3.turingwf;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Output is accumulated in memory and served to the frontend via polling
 * (POST /run → GET /status/{runId}).
 *
 * Execution is driven by SteppingInterpreter, a local Interpreter subclass that owns the
 * runUntilEnd loop so it can inject a per-step sleep without patching the turing-workflow library.
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
    public String startRun(String name, Map<String, String> inputParams, int maxIterations, long intervalMs) {
        String runId = UUID.randomUUID().toString();
        RunState state = new RunState();
        runs.put(runId, state);
        Thread.ofVirtual().name("twf-" + name)
                .start(() -> executeRun(name, inputParams, maxIterations, intervalMs, state));
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

    private void executeRun(String name, Map<String, String> inputParams,
                            int maxIterations, long intervalMs, RunState state) {
        Path file = workflowDir().resolve(name + ".yaml");
        IIActorSystem system = new IIActorSystem("twf-" + name);
        try {
            SteppingInterpreter interp = new SteppingInterpreter("twf", system, intervalMs);
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

            PrintStream prevOut = System.out;
            System.setOut(new TeePrintStream(prevOut, state));
            try {
                ActionResult result = interp.runUntilEnd(maxIterations);
                if (!result.isSuccess()) {
                    state.error = result.getResult();
                }
            } finally {
                System.setOut(prevOut);
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

    // ── Interpreter subclass ─────────────────────────────────────────────────

    /**
     * Extends Interpreter to own the runUntilEnd loop, adding an optional per-step sleep.
     * Accesses protected fields (currentState, stopRequested, logger, system) directly.
     */
    private static final class SteppingInterpreter extends Interpreter {

        private final long stepDelayMs;

        SteppingInterpreter(String loggerName, IIActorSystem sys, long stepDelayMs) {
            this.logger = Logger.getLogger(loggerName);
            this.system = sys;
            this.stepDelayMs = stepDelayMs;
        }

        @Override
        public ActionResult runUntilEnd(int maxIterations) {
            if (!hasCodeLoaded()) return new ActionResult(false, "No code loaded");
            for (int i = 0; i < maxIterations; i++) {
                if (isStopRequested()) return new ActionResult(false, "Stopped by request");
                if ("end".equals(currentState)) return new ActionResult(true, "Workflow completed");
                ActionResult r = execCode();
                if (!r.isSuccess()) {
                    return new ActionResult(false, "Workflow failed at iteration " + i + ": " + r.getResult());
                }
                if (stepDelayMs > 0) {
                    try {
                        Thread.sleep(stepDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new ActionResult(false, "Interrupted during step delay");
                    }
                }
            }
            return new ActionResult(false, "Max iterations (" + maxIterations + ") exceeded");
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

    /**
     * Tees System.out to both the original stream and the run's line buffer.
     * All output ultimately passes through write(byte[],int,int), so overriding
     * that one method plus write(int) is sufficient.
     */
    private static final class TeePrintStream extends PrintStream {
        private final RunState state;
        private final StringBuilder lineBuf = new StringBuilder();

        TeePrintStream(PrintStream delegate, RunState state) {
            super(delegate, true, StandardCharsets.UTF_8);
            this.state = state;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            String chunk = new String(buf, off, len, StandardCharsets.UTF_8);
            String[] parts = chunk.split("\n", -1);
            for (int i = 0; i < parts.length - 1; i++) {
                lineBuf.append(parts[i]);
                state.addLine(lineBuf.toString());
                lineBuf.setLength(0);
            }
            lineBuf.append(parts[parts.length - 1]);
        }

        @Override
        public void write(int b) {
            super.write(b);
            if (b == '\n') {
                state.addLine(lineBuf.toString());
                lineBuf.setLength(0);
            } else {
                lineBuf.append((char) b);
            }
        }
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
