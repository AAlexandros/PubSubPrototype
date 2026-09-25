package org.pubsub.prototype.registry.cardano;

import java.nio.file.Path;

import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

public record CardanoRegistryConfig(Path runtimeDir, String signer) {
    public CardanoRegistryConfig {
        requireNonNull(runtimeDir, CardanoRegistryNames.ConfigField.RUNTIME_DIR.value());
        signer = requireNonBlank(signer, CardanoRegistryNames.ConfigField.SIGNER.value());
    }

    public Path stateFile() {
        return runtimeDir.resolve(CardanoRegistryNames.RuntimeFile.REGISTRY_STATE.value());
    }

    public Path deploymentFile() {
        return runtimeDir.resolve(CardanoRegistryNames.RuntimeFile.DEPLOYMENT.value());
    }

    public Path transactionLogFile() {
        return runtimeDir.resolve(CardanoRegistryNames.RuntimeFile.TRANSACTION_LOG.value());
    }

    public Path transactionArtifactsDir() {
        return runtimeDir.resolve(CardanoRegistryNames.RuntimeFile.TRANSACTION_ARTIFACTS.value());
    }

    public Path scriptUtxoCacheFile() {
        return runtimeDir.resolve(CardanoRegistryNames.RuntimeFile.SCRIPT_UTXO_CACHE.value());
    }

    public Path networkEnvFile() {
        Path parent = runtimeDir.toAbsolutePath().getParent();
        String networkEnv = CardanoRegistryNames.RuntimeFile.NETWORK_ENV.value();
        return parent == null ? runtimeDir.resolve(networkEnv) : parent.resolve(networkEnv);
    }

    public Path projectRoot() {
        Path devnetDir = runtimeDir.toAbsolutePath().normalize().getParent().getParent();
        return devnetDir.getParent().getParent().getParent();
    }
}
