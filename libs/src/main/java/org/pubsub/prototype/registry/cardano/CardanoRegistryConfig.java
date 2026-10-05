package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.cardano.CardanoEnvironment;
import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.CardanoNetwork;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

public record CardanoRegistryConfig(Path runtimeDir, String signer, CardanoCliBackend cliBackend) {
    private static final String RUNTIME_DIR_FIELD = "runtimeDir";
    private static final String SIGNER_FIELD = "signer";
    private static final String CLI_BACKEND_FIELD = "cliBackend";
    private static final String REGISTRY_STATE_FILE = "registry-state.json";
    private static final String DEPLOYMENT_FILE = "deployment.env";
    private static final String TRANSACTION_LOG_FILE = "transactions.log";
    private static final String TRANSACTION_ARTIFACTS_DIRECTORY = "tx";
    private static final String SCRIPT_UTXO_CACHE_FILE = "script-utxos.json";

    public CardanoRegistryConfig {
        requireNonNull(runtimeDir, RUNTIME_DIR_FIELD);
        signer = requireNonBlank(signer, SIGNER_FIELD);
        requireNonNull(cliBackend, CLI_BACKEND_FIELD);
    }

    public Path stateFile() {
        return runtimeDir.resolve(REGISTRY_STATE_FILE);
    }

    public Path deploymentFile() {
        return runtimeDir.resolve(DEPLOYMENT_FILE);
    }

    public Path transactionLogFile() {
        return runtimeDir.resolve(TRANSACTION_LOG_FILE);
    }

    public Path transactionArtifactsDir() {
        return runtimeDir.resolve(TRANSACTION_ARTIFACTS_DIRECTORY);
    }

    public Path scriptUtxoCacheFile() {
        return runtimeDir.resolve(SCRIPT_UTXO_CACHE_FILE);
    }

    public Path networkEnvFile() {
        Path parent = runtimeDir.toAbsolutePath().getParent();
        String networkEnv = CardanoEnvironment.NETWORK_FILE;
        return parent == null ? runtimeDir.resolve(networkEnv) : parent.resolve(networkEnv);
    }

    public Path projectRoot() {
        Path devnetDir = runtimeDir.toAbsolutePath().normalize().getParent().getParent();
        return devnetDir.getParent().getParent().getParent();
    }

    Path devnetDir() {
        Path runtimeParent = runtimeDir.toAbsolutePath().normalize().getParent();
        if (runtimeParent == null || runtimeParent.getParent() == null) {
            throw new IllegalArgumentException("Unable to derive devnet directory from " + runtimeDir);
        }
        return runtimeParent.getParent();
    }

    CardanoNetwork requireNetwork(String... requiredKeys) {
        Path file = networkEnvFile();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Cardano network environment does not exist: " + file);
        }
        Map<String, String> environment = CardanoEnvironmentFile.readRequired(file, requiredKeys);
        return new CardanoNetwork(
                environment.get(CardanoEnvironment.NETWORK_MAGIC),
                environment
        );
    }
}
