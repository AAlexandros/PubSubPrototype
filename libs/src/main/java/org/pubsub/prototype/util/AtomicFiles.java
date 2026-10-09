package org.pubsub.prototype.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared file-writing operations that replace targets atomically when supported. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path directory = absoluteTarget.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, absoluteTarget.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, absoluteTarget,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
