package org.pubsub.prototype.util.files;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes files through a temporary file in the target directory, then moves that file to
 * the target. Keeping both paths in the same directory allows an atomic move on file systems
 * that support it, so readers do not observe a partially written file.
 *
 * <p>Failure behavior:
 * <ul>
 *   <li>If writing the temporary file fails, the target has not been changed; cleanup of the
 *       temporary file is attempted.</li>
 *   <li>If the process stops before the move, the target remains unchanged and the temporary
 *       file may remain because cleanup cannot run after an abrupt stop.</li>
 *   <li>If the atomic move succeeds, the target is replaced atomically where the file-system
 *       provider supports replacing an existing target during an atomic move. Java leaves that
 *       existing-target behavior provider-specific.</li>
 *   <li>If atomic moves are unsupported, this class attempts a regular replacement move. That
 *       fallback is not guaranteed to be atomic and may itself fail.</li>
 * </ul>
 *
 * <p>After a temporary file has been created, cleanup is attempted in a {@code finally} block
 * on normal Java exits, including failures. This does not guarantee that data survives a power
 * loss because the file and containing directory are not explicitly synchronized to storage.
 */
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
