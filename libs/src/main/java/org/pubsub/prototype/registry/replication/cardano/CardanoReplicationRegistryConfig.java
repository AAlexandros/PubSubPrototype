package org.pubsub.prototype.registry.replication.cardano;

import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

import java.nio.file.Path;

import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

/** Runtime paths, signer, and Cardano CLI backend for the replication registry client. */
public record CardanoReplicationRegistryConfig(Path runtimeDir, String signer, CardanoCliBackend cliBackend) {
    public CardanoReplicationRegistryConfig {
        requireNonNull(runtimeDir, "runtimeDir");
        signer = requireNonBlank(signer, "signer");
        requireNonNull(cliBackend, "cliBackend");
    }

    public Path deploymentFile() {
        return runtimeDir.resolve("deployment.env");
    }

    public Path transactionArtifactsDir() {
        return runtimeDir.resolve("tx");
    }

    public Path transactionLogFile() {
        return runtimeDir.resolve("transactions.log");
    }

    public Path scriptUtxoCacheFile() {
        return runtimeDir.resolve("script-utxos.json");
    }

    public Path stateFile() {
        return runtimeDir.resolve("servers.json");
    }

    public Path projectRoot() {
        Path devnetDir = runtimeDir.toAbsolutePath().normalize().getParent().getParent();
        Path opsDir = devnetDir.getParent().getParent();
        Path projectRoot = opsDir.getParent();
        if (projectRoot == null) throw new IllegalStateException("Unable to derive project root from " + runtimeDir);
        return projectRoot;
    }

    public Path keysDirectory() {
        return runtimeDir.toAbsolutePath().normalize().getParent().getParent().resolve("keys");
    }
}
