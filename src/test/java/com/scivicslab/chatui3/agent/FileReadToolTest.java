package com.scivicslab.chatui3.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("file_read.01")
@DisplayName("FileReadTool — reads files/dirs under the working directory, rejects escapes, caps recursion")
class FileReadToolTest {

    @Test
    void readsSingleFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("note.md"), "hello SENTINEL world");
        String out = FileReadTool.read(root, "note.md");
        assertEquals("hello SENTINEL world", out);
    }

    @Test
    void readsDirectoryRecursively_withHeaders(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs/sub"));
        Files.writeString(root.resolve("docs/a.md"), "AAA");
        Files.writeString(root.resolve("docs/sub/b.md"), "BBB");

        String out = FileReadTool.read(root, "docs");

        assertTrue(out.contains("AAA"), "first file content present");
        assertTrue(out.contains("BBB"), "nested file content present");
        assertTrue(out.contains("docs/a.md") || out.contains("docs\\a.md"), "per-file header present");
        assertTrue(out.contains("sub"), "nested path shown");
    }

    @Test
    void rejectsPathEscape(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("inside.txt"), "x");
        String out = FileReadTool.read(root, "../etc/passwd");
        assertTrue(out.startsWith("error: path escapes") || out.startsWith("error: not found"),
                "escape rejected, got: " + out);
    }

    @Test
    void notFound(@TempDir Path root) {
        String out = FileReadTool.read(root, "nope.md");
        assertTrue(out.startsWith("error: not found"), out);
    }

    @Test
    void blankInput() {
        String out = FileReadTool.read(Path.of(""), "  ");
        assertTrue(out.startsWith("error: path required"), out);
    }

    @Test
    void directoryRecursion_isCapped(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("many"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("many/f" + i + ".txt"), "content" + i);
        }
        // Cap at 2 files: must stop and mark truncation.
        String out = FileReadTool.read(root, "many", 1_000_000, 2);
        assertTrue(out.contains("truncated"), "cap marker present");
        assertFalse(out.contains("content4"), "files beyond the cap are not read");
    }
}
