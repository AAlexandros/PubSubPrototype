package org.pubsub.prototype.registry.topic.cardano.transaction;

import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.util.files.TextFiles;

import java.nio.file.Path;

/** Owns the names, locations, and persistence of generated Cardano transaction files. */
public final class CardanoTransactionArtifacts {
    private static final String DATUM_SUFFIX = ".datum.json";
    private static final String REDEEMER_SUFFIX = ".redeemer.json";
    private static final String TRANSACTION_BODY_SUFFIX = ".txbody";
    private static final String SIGNED_TRANSACTION_SUFFIX = ".tx";
    private static final String MINT_REDEEMER_NAME = "createTopic";

    private final CardanoRegistryConfig config;

    public CardanoTransactionArtifacts(CardanoRegistryConfig config) {
        this.config = config;
    }

    public Path writeDatum(String artifactName, String content) {
        return write(artifactName + DATUM_SUFFIX, content);
    }

    public Path writeMintRedeemer(String content) {
        return write(MINT_REDEEMER_NAME + REDEEMER_SUFFIX, content);
    }

    public Path writeTopicRedeemer(String operation, String content) {
        return write(operation + REDEEMER_SUFFIX, content);
    }

    public Path transactionBody(String artifactName) {
        return prepare(artifactName + TRANSACTION_BODY_SUFFIX);
    }

    public Path signedTransaction(String artifactName) {
        return prepare(artifactName + SIGNED_TRANSACTION_SUFFIX);
    }

    private Path prepare(String filename) {
        return TextFiles.prepare(config.transactionArtifactsDir().resolve(filename));
    }

    private Path write(String filename, String content) {
        return TextFiles.write(config.transactionArtifactsDir().resolve(filename), content);
    }
}
