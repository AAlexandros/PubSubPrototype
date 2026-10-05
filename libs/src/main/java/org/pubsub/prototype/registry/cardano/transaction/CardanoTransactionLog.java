package org.pubsub.prototype.registry.cardano.transaction;

import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.util.TextFiles;

import java.time.Instant;

/** Appends outcomes of Cardano transaction submissions to the registry transaction log. */
final class CardanoTransactionLog {
    private final CardanoRegistryConfig config;

    CardanoTransactionLog(CardanoRegistryConfig config) {
        this.config = config;
    }

    void append(String line) {
        TextFiles.append(
                config.transactionLogFile(),
                Instant.now() + " " + line + System.lineSeparator()
        );
    }
}
