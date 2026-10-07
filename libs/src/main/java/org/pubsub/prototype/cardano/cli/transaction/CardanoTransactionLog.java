package org.pubsub.prototype.cardano.cli.transaction;

import org.pubsub.prototype.util.TextFiles;

import java.nio.file.Path;
import java.time.Instant;

/** Appends timestamped outcomes from signed Cardano transaction submissions. */
public final class CardanoTransactionLog {
    private final Path file;

    public CardanoTransactionLog(Path file) {
        this.file = file;
    }

    public void append(String line) {
        TextFiles.append(file, Instant.now() + " " + line + System.lineSeparator());
    }
}
