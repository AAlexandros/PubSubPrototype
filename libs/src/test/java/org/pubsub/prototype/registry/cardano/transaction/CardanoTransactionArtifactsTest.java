package org.pubsub.prototype.registry.cardano.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoTransactionArtifactsTest {
    @TempDir
    Path tempDir;

    @Test
    void ownsTransactionArtifactNamesAndLocations() throws IOException {
        CardanoRegistryConfig config = new CardanoRegistryConfig(
                tempDir.resolve("runtime/registry"),
                "registry-deployer",
                CardanoCliBackend.CACHE_ONLY
        );
        CardanoTransactionArtifacts artifacts = new CardanoTransactionArtifacts(config);

        Path datum = artifacts.writeDatum("create-topic", "datum");
        Path mintRedeemer = artifacts.writeMintRedeemer("mint");
        Path topicRedeemer = artifacts.writeTopicRedeemer("add-admin", "mutation");
        Path transactionBodyFile = artifacts.transactionBody("create-topic");
        Path signedTransactionFile = artifacts.signedTransaction("create-topic");

        assertEquals(config.transactionArtifactsDir().resolve("create-topic.datum.json"), datum);
        assertEquals(config.transactionArtifactsDir().resolve("createTopic.redeemer.json"), mintRedeemer);
        assertEquals(config.transactionArtifactsDir().resolve("add-admin.redeemer.json"), topicRedeemer);
        assertEquals(config.transactionArtifactsDir().resolve("create-topic.txbody"), transactionBodyFile);
        assertEquals(config.transactionArtifactsDir().resolve("create-topic.tx"), signedTransactionFile);
        assertEquals("datum", Files.readString(datum));
        assertEquals("mint", Files.readString(mintRedeemer));
        assertEquals("mutation", Files.readString(topicRedeemer));
        assertTrue(Files.isDirectory(transactionBodyFile.getParent()));
    }
}
