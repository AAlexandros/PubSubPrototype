package org.pubsub.prototype.persistence.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFiles {
    private AtomicFiles() {
    }

    static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
