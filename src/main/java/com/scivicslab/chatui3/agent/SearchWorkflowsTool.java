package com.scivicslab.chatui3.agent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * The {@code search_tools} agent tool: searches the user's Turing Workflow YAML files in
 * {@code ~/works/workflow/} by meaning (name, description, tags) and returns the top matches
 * with their required parameters so the LLM knows what to pass to {@code run_workflow}.
 *
 * <p>This is intentionally text-only (no embedding server dependency): it scores each workflow
 * by how many query words appear in its name, description, and tags, then returns the top-ranked
 * results. The goal is to keep the agent's tool list short — the LLM searches here first, then
 * calls {@code run_workflow} with the specific workflow name.</p>
 */
public final class SearchWorkflowsTool {

    private SearchWorkflowsTool() {}

    private static final Logger LOG = Logger.getLogger(SearchWorkflowsTool.class.getName());
    static final int MAX_RESULTS = 5;

    /** Parsed representation of one workflow's metadata. */
    private record WorkflowMeta(
            String fileName,
            String name,
            String description,
            List<String> tags,
            Map<String, String> params  // param name → description
    ) {}

    /**
     * Searches available workflows matching {@code query} and returns formatted results including
     * each workflow's name, description, tags, and required parameters.
     */
    public static String search(String query) {
        if (query == null || query.isBlank()) return "error: query required";

        Path wfDir = workflowDir();
        if (!Files.isDirectory(wfDir)) {
            return "No workflow directory found at " + wfDir
                    + ". Create YAML files there to register workflows.";
        }

        List<WorkflowMeta> all = loadAll(wfDir);
        if (all.isEmpty()) {
            return "No workflows found in " + wfDir + ".";
        }

        String[] words = query.toLowerCase().split("\\s+");
        List<WorkflowMeta> ranked = all.stream()
                .filter(wf -> score(wf, words) > 0)
                .sorted(Comparator.comparingInt((WorkflowMeta wf) -> score(wf, words)).reversed())
                .limit(MAX_RESULTS)
                .toList();

        if (ranked.isEmpty()) {
            // No match — show all workflows (capped) so the LLM can browse
            ranked = all.stream().limit(MAX_RESULTS).toList();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Workflows matching '").append(query).append("':\n\n");
        for (int i = 0; i < ranked.size(); i++) {
            WorkflowMeta wf = ranked.get(i);
            sb.append(i + 1).append(". **").append(wf.name()).append("**\n");
            if (!wf.description().isBlank()) {
                // First non-blank line of the description
                String firstLine = wf.description().lines()
                        .filter(l -> !l.isBlank()).findFirst().orElse("").trim();
                sb.append("   ").append(firstLine).append("\n");
            }
            if (!wf.tags().isEmpty()) {
                sb.append("   tags: ").append(String.join(", ", wf.tags())).append("\n");
            }
            if (!wf.params().isEmpty()) {
                sb.append("   params (pass as JSON to run_workflow):\n");
                for (Map.Entry<String, String> e : wf.params().entrySet()) {
                    String desc = e.getValue().isBlank() ? "" : " — " + e.getValue();
                    sb.append("     \"").append(e.getKey()).append("\"").append(desc).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("To run a workflow: run_workflow(workflow=\"<name>\", params=\"{\\\"key\\\": \\\"value\\\", ...}\")");
        return sb.toString().stripTrailing();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static Path workflowDir() {
        String home = System.getProperty("user.home", System.getenv().getOrDefault("HOME", "~"));
        return Path.of(home, "works", "workflow");
    }

    private static List<WorkflowMeta> loadAll(Path dir) {
        List<WorkflowMeta> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                  .sorted()
                  .forEach(p -> {
                      WorkflowMeta m = parse(p);
                      if (m != null) result.add(m);
                  });
        } catch (IOException e) {
            LOG.warning("search_tools: cannot list workflows: " + e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static WorkflowMeta parse(Path yamlFile) {
        try {
            String yaml = Files.readString(yamlFile);
            Yaml parser = new Yaml();
            Object loaded = parser.load(yaml);
            if (!(loaded instanceof Map<?, ?> doc)) return null;

            String fileName = yamlFile.getFileName().toString().replaceFirst("\\.yaml$", "");
            String name = Objects.toString(doc.get("name"), fileName);
            String description = Objects.toString(doc.get("description"), "").strip();

            List<String> tags = new ArrayList<>();
            Object tagsRaw = doc.get("tags");
            if (tagsRaw instanceof List<?> tagList) {
                for (Object t : tagList) tags.add(String.valueOf(t));
            }

            Map<String, String> params = new java.util.LinkedHashMap<>();
            Object paramsRaw = doc.get("params");
            if (paramsRaw instanceof Map<?, ?> paramsMap) {
                for (Map.Entry<?, ?> e : paramsMap.entrySet()) {
                    String pName = String.valueOf(e.getKey());
                    String pDesc = "";
                    if (e.getValue() instanceof Map<?, ?> meta) {
                        pDesc = Objects.toString(meta.get("description"), "").strip();
                        Object def = meta.get("default");
                        if (def != null) pDesc += " (default: " + def + ")";
                    } else if (e.getValue() != null) {
                        pDesc = String.valueOf(e.getValue());
                    }
                    params.put(pName, pDesc);
                }
            }

            return new WorkflowMeta(fileName, name, description, tags, params);
        } catch (Exception e) {
            LOG.warning("search_tools: cannot parse " + yamlFile + ": " + e.getMessage());
            return null;
        }
    }

    /** Scores a workflow by how many query words appear in its searchable text. */
    private static int score(WorkflowMeta wf, String[] words) {
        String text = (wf.name() + " " + wf.description() + " " + String.join(" ", wf.tags()))
                .toLowerCase();
        int hits = 0;
        for (String word : words) {
            if (text.contains(word)) hits++;
        }
        return hits;
    }
}
