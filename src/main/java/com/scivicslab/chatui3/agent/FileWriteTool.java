package com.scivicslab.chatui3.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code write} tool: saves text to a file under the working directory and returns a short
 * confirmation the agent feeds back as an Observation. The mutating counterpart to {@link FileReadTool}.
 *
 * <p>Writes are confined to the working directory, mirroring the read tool's trust boundary: the
 * resolved path must stay inside the working directory (a {@code ..} escape is rejected on the
 * normalized path before anything is created, and the parent's real path is re-checked after it
 * exists). Existing directories are never overwritten.</p>
 */
public final class FileWriteTool {

    private FileWriteTool() {}

    /**
     * Writes {@code content} to {@code path} (relative to {@code root}), creating parent directories as
     * needed. Returns a confirmation, or an {@code error: ...} string the agent feeds back as the
     * Observation.
     */
    public static String write(Path root, String path, String content) {
        if (path == null || path.isBlank()) return "error: path required";
        try {
            Path base = root.toAbsolutePath().normalize();
            // Expand ~ / $HOME the same way the read tool does; confinement below still restricts the result.
            String p = FileReadTool.expandHome(path.trim());
            Path target = base.resolve(p).normalize();
            // Reject escapes on the normalized path BEFORE creating anything.
            if (!target.startsWith(base)) {
                return "error: path escapes working directory: " + path;
            }
            if (Files.isDirectory(target)) {
                return "error: path is a directory: " + path;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                // Re-check via the real path now that the parent exists (catches symlink escapes).
                if (!parent.toRealPath().startsWith(base)) {
                    return "error: path escapes working directory: " + path;
                }
            }
            String body = content == null ? "" : content;
            Files.writeString(target, body);
            return "wrote " + base.relativize(target) + " (" + body.length() + " chars)";
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}
