package org.pubsub.prototype.registry.cardano;

import java.nio.file.Path;
import java.util.Objects;

public record CardanoRegistryConfig(Path runtimeDir, String signer) {
    public CardanoRegistryConfig {
        Objects.requireNonNull(runtimeDir, CardanoRegistryNames.ConfigField.RUNTIME_DIR.value());
        Objects.requireNonNull(signer, CardanoRegistryNames.ConfigField.SIGNER.value());
        if (signer.isBlank()) {
            throw new IllegalArgumentException(CardanoRegistryNames.ConfigField.SIGNER.value() + " is required");
        }
    }

    public Path stateFile() {
        return runtimeDir.resolve("registry-state.json");
    }

    public Path deploymentFile() {
        return runtimeDir.resolve("deployment.env");
    }

    public Path transactionLogFile() {
        return runtimeDir.resolve("transactions.log");
    }

    public Path transactionArtifactsDir() {
        return runtimeDir.resolve("tx");
    }

    public Path scriptUtxoCacheFile() {
        return runtimeDir.resolve("script-utxos.json");
    }

    public Path networkEnvFile() {
        Path parent = runtimeDir.toAbsolutePath().getParent();
        return parent == null ? runtimeDir.resolve("network.env") : parent.resolve("network.env");
    }

    public Path projectRoot() {
        Path devnetDir = runtimeDir.toAbsolutePath().normalize().getParent().getParent();
        return devnetDir.getParent().getParent().getParent();
    }
}
