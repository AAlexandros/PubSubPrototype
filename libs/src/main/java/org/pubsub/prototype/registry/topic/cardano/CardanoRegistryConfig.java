package org.pubsub.prototype.registry.topic.cardano;

import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

import java.nio.file.Path;

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

    public Path projectRoot() {
        Path devnetDir = runtimeDir.toAbsolutePath().normalize().getParent().getParent();
        return devnetDir.getParent().getParent().getParent();
    }

}
