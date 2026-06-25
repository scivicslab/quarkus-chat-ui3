package com.scivicslab.chatui3.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Catalogue of the agent loop's Turing Workflows, for the right-pane Workflow tab.
 *
 * <p>Phase 1 (this class): exposes the SYSTEM workflows — the loop itself ({@code agent-react}) and the
 * dispatch-target workflows — as read-only YAML loaded from the bundled {@code /workflows/} resources.
 * The system YAML is display-only and cannot be changed (per the design). A later phase adds separate,
 * user-editable extension files invoked at insertion points; this catalogue is the foundation for that.</p>
 */
@ApplicationScoped
public class WorkflowCatalog {

    /** A workflow the editor can show: its {@code name} (= resource basename) and a human title. */
    public record WorkflowInfo(String name, String title) {}

    /** The bundled system workflows, in display order. {@code name} maps to {@code /workflows/<name>.yaml}. */
    private static final List<WorkflowInfo> SYSTEM = List.of(
            new WorkflowInfo("agent-react",        "Agent loop (ReAct)"),
            new WorkflowInfo("understand-project", "Understand project"),
            new WorkflowInfo("summarize-file",     "Summarize file (sub)"),
            new WorkflowInfo("translate-document", "Translate document"),
            new WorkflowInfo("translate-chunk",    "Translate chunk (sub)"));

    /** Lists the system workflows available to view. */
    public List<WorkflowInfo> list() {
        return SYSTEM;
    }

    /** True if {@code name} is one of the known system workflows. */
    public boolean isKnown(String name) {
        return name != null && SYSTEM.stream().anyMatch(w -> w.name().equals(name));
    }

    /** Returns the bundled (read-only) YAML for {@code name}, or null if unknown / not found. */
    public String systemYaml(String name) {
        if (!isKnown(name)) return null;
        try (InputStream in = getClass().getResourceAsStream("/workflows/" + name + ".yaml")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
