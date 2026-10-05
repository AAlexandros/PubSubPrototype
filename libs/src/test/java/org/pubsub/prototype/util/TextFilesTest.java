package org.pubsub.prototype.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void preparesParentDirectoriesForExternalWriters() {
        Path file = tempDir.resolve("nested/output.txt");

        assertEquals(file, TextFiles.prepare(file));
        assertTrue(Files.isDirectory(file.getParent()));
    }

    @Test
    void writesReadsAndAppendsUtf8Text() {
        Path file = tempDir.resolve("nested/output.txt");

        TextFiles.write(file, "α");
        TextFiles.append(file, "β");

        assertEquals("αβ", TextFiles.read(file));
    }

    @Test
    void translatesIoFailuresAtTheUtilityBoundary() {
        assertThrows(IllegalStateException.class, () -> TextFiles.write(tempDir, "content"));
        assertThrows(IllegalStateException.class, () -> TextFiles.read(tempDir));
    }
}
