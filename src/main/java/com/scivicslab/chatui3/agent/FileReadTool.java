package com.scivicslab.chatui3.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code read} ReAct tool: reads a file, or recursively reads a directory, under the working
 * directory and returns the text as an Observation.
 *
 * <p>Reads are confined to the working directory: the resolved real path (symlinks included) must be
 * inside the working directory's real path, otherwise the request is rejected. This keeps the tool's
 * reach matched to the trust boundary (no unrestricted POSIX access from the Web side).</p>
 *
 * <p>Directory recursion is capped (total bytes and file count) so a huge tree cannot exhaust memory;
 * the {@code s_budget} truncation only shrinks the copy sent to the model, not the Java string built
 * here, so this independent cap is required.</p>
 */
public final class FileReadTool {

    private FileReadTool() {}

    /** Total characters read for a directory before truncating. */
    static final long MAX_TOTAL_CHARS = 4_000_000;
    /** Maximum number of files read for a directory. */
    static final int MAX_FILES = 1000;

    /** Build/VCS/IDE/dependency directories skipped when reading a directory (noise, not source). */
    static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "target", "build", "dist", "out", "bin", "node_modules",
            ".git", ".gradle", ".idea", ".mvn", ".vscode", ".settings");
    /** File extensions skipped when reading a directory (binary / generated, not readable source). */
    static final java.util.Set<String> SKIP_EXT = java.util.Set.of(
            "class", "jar", "war", "ear", "zip", "gz", "tar", "tgz", "so", "o", "a", "dll", "exe",
            "bin", "png", "jpg", "jpeg", "gif", "ico", "svg", "pdf", "woff", "woff2", "ttf", "eot",
            "mp4", "mp3", "wav", "lock", "p12", "jks", "keystore");

    /**
     * Reads {@code input} (a path relative to {@code root}). Returns file/directory text, or an
     * {@code error: ...} string the agent feeds back as the Observation.
     */
    public static String read(Path root, String input) {
        return read(root, input, MAX_TOTAL_CHARS, MAX_FILES);
    }

    /** As {@link #read(Path, String)} but with explicit caps (used by tests). */
    static String read(Path root, String input, long maxChars, int maxFiles) {
        if (input == null || input.isBlank()) {
            return "error: path required";
        }
        try {
            Path base = root.toAbsolutePath().normalize();
            // Accept how users actually write paths: expand ~ and $HOME, and allow absolute paths.
            // The confinement check below still restricts the result to the working directory, so this
            // only changes how the path is spelled, not what may be read.
            String p = expandHome(input.trim());
            Path target = base.resolve(p).normalize();
            if (!Files.exists(target)) {
                return "error: not found: " + input;
            }
            // Resolve symlinks and confirm the target stays inside the working directory.
            Path realBase = base.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                return "error: path escapes working directory: " + input;
            }
            if (Files.isDirectory(realTarget)) {
                return readDirectory(realBase, realTarget, maxChars, maxFiles);
            }
            return Files.readString(realTarget);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /** True if a file is inside a skipped directory or has a skipped (binary/generated) extension. */
    static boolean isSkipped(Path dir, Path f) {
        for (Path seg : dir.relativize(f)) {
            if (SKIP_DIRS.contains(seg.toString())) return true;
        }
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SKIP_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Expands a leading {@code ~} or {@code $HOME} to the user's home directory; leaves the rest as-is. */
    static String expandHome(String p) {
        String home = System.getProperty("user.home", "");
        if (home.isEmpty()) return p;
        if (p.equals("~") || p.equals("$HOME")) return home;
        if (p.startsWith("~/")) return home + p.substring(1);
        if (p.startsWith("$HOME/")) return home + p.substring(5);
        return p;
    }

    private static String readDirectory(Path base, Path dir, long maxChars, int maxFiles) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(f -> !isSkipped(dir, f))   // drop build/VCS/binary noise (target/, .git/, *.class …)
                    .sorted().toList();
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        long chars = 0;
        for (Path f : files) {
            if (count >= maxFiles || chars >= maxChars) {
                sb.append("\n…(truncated: cap reached after ").append(count).append(" files)…\n");
                break;
            }
            String content;
            try {
                content = Files.readString(f);   // UTF-8; skip files that are not readable text
            } catch (IOException e) {
                continue;
            }
            sb.append("===== ").append(base.relativize(f)).append(" =====\n")
              .append(content).append("\n\n");
            count++;
            chars += content.length();
        }
        if (sb.length() == 0) {
            return "(no readable files under " + base.relativize(dir) + ")";
        }
        return sb.toString();
    }
}
