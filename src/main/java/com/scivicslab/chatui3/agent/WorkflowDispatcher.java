package com.scivicslab.chatui3.agent;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import jakarta.enterprise.context.ApplicationScoped;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches a well-defined ("known") request to a deterministic Turing Workflow and returns the
 * generated document. This is the "確実に既知問題を" half of the design (130_WorkflowDispatch): the agent
 * loop's light judgment is to CALL this for a known task; everything inside here is mechanical.
 *
 * <p>Two task types are wired:</p>
 * <ul>
 *   <li>{@code understand_project} — scan a code project, summarize every file in parallel, and
 *       synthesize a single overview document ({@code understand-project.yaml}). Handles projects too
 *       large to fit one prompt, via map-reduce.</li>
 *   <li>{@code translate_document} — split a document and translate it faithfully into Japanese in
 *       parallel, then join ({@code translate-document.yaml}).</li>
 * </ul>
 *
 * <p>The workflow YAMLs are bundled as resources and extracted to a temp directory at runtime, because
 * the {@code parallel-map} built-in references its sub-workflow by file path (a classpath resource has
 * no path). The workflow's own actors (codedoc / llm) are created by {@code loader.createChild}, which
 * resolves them via {@code Class.forName} from the app classpath (the plugins are compile dependencies),
 * so no runtime {@code .m2} resolution is required.</p>
 */
@ApplicationScoped
public class WorkflowDispatcher {

    private static final Logger LOG = Logger.getLogger(WorkflowDispatcher.class.getName());

    /** Bundled workflow resources under {@code /workflows/} that must be extracted together. */
    private static final List<String> WORKFLOW_FILES = List.of(
            "understand-project.yaml", "summarize-file.yaml",
            "translate-document.yaml", "translate-chunk.yaml");

    /** Parent step count is tiny; this only bounds the engine, parallel-map sub-runs are separate. */
    private static final int MAX_ITERATIONS = 1_000_000;

    /** A known task: the parent workflow file and the param name that carries the target path. */
    private record Task(String workflowFile, String pathParam, boolean isProject) {}

    private static final Map<String, Task> TASKS = Map.of(
            "understand_project", new Task("understand-project.yaml", "dir", true),
            "translate_document", new Task("translate-document.yaml", "in", false));

    /** Extracted-once temp directory holding the workflow YAMLs. */
    private volatile Path workflowDir;

    /** True if {@code task} names a known, bundled workflow task. */
    public boolean isKnownTask(String task) {
        return task != null && TASKS.containsKey(task);
    }

    /**
     * Runs a user-defined external workflow from {@code ~/works/workflow/<name>.yaml}.
     * Injects an {@code out} parameter pointing to a temp file; reads and returns that file's
     * contents as the observation after the workflow completes.
     *
     * <p>The workflow YAML must accept an {@code out} parameter and write its result there
     * (e.g. using {@code ocr.writeFile}). If the file is empty after completion, the workflow's
     * console output is returned instead.</p>
     *
     * @param name       workflow filename without the {@code .yaml} extension
     * @param userParams workflow-specific parameters supplied by the LLM
     * @return the workflow's text result, or an {@code error: ...} string on failure
     */
    public String runExternal(String name, Map<String, String> userParams) {
        if (name == null || name.isBlank()) {
            return "error: workflow name required";
        }
        String home = System.getProperty("user.home", System.getenv().getOrDefault("HOME", "~"));
        Path wfDir  = Path.of(home, "works", "workflow");
        Path yamlFile = wfDir.resolve(name + ".yaml");
        if (!Files.exists(yamlFile)) {
            return "error: workflow not found: " + name
                    + " (looked in " + wfDir + "). Call search_tools to find available workflows.";
        }
        try {
            Path out = Files.createTempFile("chatui3-ext-" + name + "-", ".md");
            Map<String, String> params = new LinkedHashMap<>(userParams != null ? userParams : Map.of());
            params.put("out", out.toString());

            LOG.info("runExternal: workflow=" + name + " out=" + out + " params=" + params.keySet());
            runExternalWorkflow(yamlFile, params);

            String body = Files.readString(out);
            try { Files.deleteIfExists(out); } catch (Exception ignore) { /* best effort */ }

            if (body.isBlank()) {
                return "error: workflow '" + name + "' completed but wrote no output to the 'out' file. "
                        + "The workflow YAML must write its result using ocr.writeFile or similar.";
            }
            return "Workflow '" + name + "' result:\n\n" + body;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "runExternal failed for workflow " + name, e);
            return "error: workflow '" + name + "' failed: " + e.getMessage();
        }
    }

    /** Runs one external workflow YAML file to completion, seeding the given params. */
    private void runExternalWorkflow(Path yamlFile, Map<String, String> params) throws Exception {
        IIActorSystem system = new IIActorSystem("dispatch-ext");
        try {
            Interpreter interp = new Interpreter.Builder()
                    .loggerName("dispatch-ext")
                    .team(system)
                    .build();
            interp.setWorkflowBaseDir(yamlFile.getParent().toString());

            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interp, system);
            interp.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);

            try (InputStream in = Files.newInputStream(yamlFile)) {
                interp.readYaml(in);
            }
            for (Map.Entry<String, String> e : params.entrySet()) {
                interpreterActor.callByActionName("putJson", new JSONObject()
                        .put("path", e.getKey())
                        .put("value", e.getValue())
                        .toString());
            }

            ActionResult r = interp.runUntilEnd(MAX_ITERATIONS);
            if (!r.isSuccess()) {
                throw new IllegalStateException("workflow did not complete: " + r.getResult());
            }
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }

    /**
     * Runs the workflow for {@code task} over {@code path} and returns the generated document text.
     * Returns an {@code error: ...} string (never throws) so the agent loop can feed it back as the
     * observation, matching the {@code read}/{@code calc} tools' contract.
     */
    public String run(String task, String path) {
        Task t = TASKS.get(task);
        if (t == null) {
            return "error: unknown task '" + task + "'. Known: " + TASKS.keySet();
        }
        if (path == null || path.isBlank()) {
            return "error: path required for task '" + task + "'";
        }
        // Resolve the target the same way the read tool does: expand ~/$HOME, confine to the working dir.
        Path target;
        try {
            Path base = Path.of("").toAbsolutePath().normalize();
            String p = FileReadTool.expandHome(path.trim());
            target = base.resolve(p).normalize();
            if (!Files.exists(target)) {
                return "error: not found: " + path;
            }
            Path realBase = base.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                return "error: path escapes working directory: " + path;
            }
            target = realTarget;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }

        try {
            Path dir = ensureWorkflows();
            Path out = Files.createTempFile("chatui3-" + task + "-", ".md");

            Map<String, String> params = new LinkedHashMap<>();
            params.put(t.pathParam(), target.toString());
            params.put("out", out.toString());
            params.put("wf.dir", dir.toString());

            LOG.info("dispatch: task=" + task + " target=" + target + " -> " + out);
            runWorkflow(t.workflowFile(), params, dir);

            String body = Files.readString(out);
            try { Files.deleteIfExists(out); } catch (Exception ignore) { /* best effort */ }

            String header = t.isProject()
                    ? "Generated project overview (detected build type: " + detectProjectType(target)
                      + "). The document below was produced by the understand-project workflow "
                      + "(scan -> parallel per-file summary -> synthesis):\n\n"
                    : "Faithful Japanese translation produced by the translate-document workflow "
                      + "(chunk -> parallel translate -> join):\n\n";
            return header + body;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "dispatch failed for task " + task, e);
            return "error: workflow '" + task + "' failed: " + e.getMessage();
        }
    }

    /** Extracts the bundled workflow YAMLs to a temp directory once, returning that directory. */
    private synchronized Path ensureWorkflows() throws Exception {
        if (workflowDir != null && Files.isDirectory(workflowDir)) {
            return workflowDir;
        }
        Path d = Files.createTempDirectory("chatui3-workflows");
        for (String f : WORKFLOW_FILES) {
            try (InputStream in = getClass().getResourceAsStream("/workflows/" + f)) {
                if (in == null) {
                    throw new IllegalStateException("workflow resource missing: /workflows/" + f);
                }
                Files.copy(in, d.resolve(f));
            }
        }
        workflowDir = d;
        LOG.info("extracted workflows to " + d);
        return d;
    }

    /**
     * Runs one workflow to completion in an embedded interpreter, mirroring the CLI runner's built-in
     * actors. Parameters are seeded into the interpreter's JSON state AFTER readYaml so they override
     * the workflow's declared defaults. Throws on failure (caller converts to an {@code error:} string).
     */
    private void runWorkflow(String yamlFile, Map<String, String> params, Path baseDir) throws Exception {
        IIActorSystem system = new IIActorSystem("dispatch");
        try {
            Interpreter interp = new Interpreter.Builder()
                    .loggerName("dispatch")
                    .team(system)
                    .build();
            interp.setWorkflowBaseDir(baseDir.toString());

            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interp, system);
            interp.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);

            try (InputStream in = Files.newInputStream(baseDir.resolve(yamlFile))) {
                interp.readYaml(in);
            }
            for (Map.Entry<String, String> e : params.entrySet()) {
                interpreterActor.callByActionName("putJson", new JSONObject()
                        .put("path", e.getKey())
                        .put("value", e.getValue())
                        .toString());
            }

            ActionResult r = interp.runUntilEnd(MAX_ITERATIONS);
            if (!r.isSuccess()) {
                throw new IllegalStateException("workflow did not complete: " + r.getResult());
            }
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }

    /** Deterministic build-type detection (no LLM): the mechanical fact reported in the result header. */
    private static String detectProjectType(Path dir) {
        if (Files.exists(dir.resolve("pom.xml"))) return "Java/Maven";
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts")))
            return "Java/Gradle";
        if (Files.exists(dir.resolve("pyproject.toml")) || Files.exists(dir.resolve("requirements.txt")))
            return "Python";
        if (Files.exists(dir.resolve("package.json"))) return "Node.js";
        return "unknown";
    }
}
