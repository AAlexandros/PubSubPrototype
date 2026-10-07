package org.pubsub.prototype.registry.topic.cardano;

import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.cli.CardanoCliUnavailableException;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.util.TextFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.StandardCopyOption;

/** Manages Cardano registry scripts and persisted deployment metadata. */
public final class CardanoRegistryDeploymentManager {
    private static final String EMPTY_JSON_OBJECT = "{}";
    private static final String TOPIC_STATE_SCRIPT = "topic-state.plutus.json";
    private static final String TOPIC_POLICY_SCRIPT = "topic-policy.plutus.json";
    private static final String SCRIPTS_DIRECTORY = "scripts";
    private static final Path CARDANO_SCRIPT_BUILD_DIRECTORY =
            Path.of("contracts", "topic-registry", "build", "cardano-cli");

    private final CardanoRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private RegistryDeployment cachedDeployment;

    public CardanoRegistryDeploymentManager(CardanoRegistryConfig config, CardanoCommandRunner commandRunner) {
        this.config = config;
        this.commandRunner = commandRunner;
    }

    /** Deploys the registry scripts and persists the resulting identifiers. */
    public synchronized RegistryDeployment initialize() {
        RegistryDeployment deployment = createDeployment();
        write(deployment);
        cachedDeployment = deployment;
        return deployment;
    }

    /**
     * Returns the deployment cached by this manager, loading or creating it on first access.
     * External changes to the deployment file require a new manager instance.
     */
    public synchronized RegistryDeployment read() {
        if (cachedDeployment != null) {
            return cachedDeployment;
        }
        if (!Files.exists(config.deploymentFile())) {
            return initialize();
        }
        Map<String, String> values = CardanoEnvironmentFile.readRequired(
                config.deploymentFile(),
                CardanoRegistryEnvironment.TOPIC_REGISTRY_VALIDATOR_ADDRESS,
                CardanoRegistryEnvironment.TOPIC_POLICY_ID
        );
        cachedDeployment = new RegistryDeployment(
                values.get(CardanoRegistryEnvironment.TOPIC_REGISTRY_VALIDATOR_ADDRESS),
                values.get(CardanoRegistryEnvironment.TOPIC_POLICY_ID)
        );
        return cachedDeployment;
    }

    /** Returns the staged topic-state validator script. */
    public Path validatorScript() {
        return requireStagedScript(TOPIC_STATE_SCRIPT);
    }

    /** Returns the staged topic minting-policy script. */
    public Path mintingPolicyScript() {
        return requireStagedScript(TOPIC_POLICY_SCRIPT);
    }

    /**
     * Copies the two plutus scripts, one is the minting policy, used for creatomg a topic's unique identifying token,
     * and the other is the validator script, used for managing the topic's state.
     */
    private RegistryDeployment createDeployment() {
        requireCommandExecution();
        Path validator = stageScript(TOPIC_STATE_SCRIPT);
        Path policy = stageScript(TOPIC_POLICY_SCRIPT);
        // The validator address is derived from the topic state script hash
        // cardano-cli address build: derive the testnet address whose payment credential is this validator script.
        String validatorAddress = commandRunner.run(
                "address", // Top-level, era-independent address command group.
                "build", // Build an address from the supplied credential.
                "--payment-script-file", validator.toString(), // Use the topic-state validator as the payment credential.
                "--testnet-magic", commandRunner.networkMagic() // Encode the address for the configured test network.
        ).trim();
        // cardano-cli latest transaction policyid: derive the topic minting policy's script hash.
        String policyId = commandRunner.run(
                "latest", // Use the transaction commands for the newest ledger era supported by cardano-cli.
                "transaction", // Select the transaction command group.
                "policyid", // Calculate a minting-policy ID from a script.
                "--script-file", policy.toString() // The compiled topic-policy Plutus script.
        ).trim();
        return new RegistryDeployment(validatorAddress, policyId);
    }

    // Helper methods 

    private void write(RegistryDeployment deployment) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CardanoRegistryEnvironment.TOPIC_REGISTRY_VALIDATOR_ADDRESS, deployment.validatorAddress());
        values.put(CardanoRegistryEnvironment.TOPIC_POLICY_ID, deployment.policyId());
        CardanoEnvironmentFile.write(config.deploymentFile(), values);
        if (!Files.exists(config.scriptUtxoCacheFile())) {
            TextFiles.write(config.scriptUtxoCacheFile(), EMPTY_JSON_OBJECT);
        }
    }

    private Path stageScript(String filename) {
        Path source = config.projectRoot().resolve(CARDANO_SCRIPT_BUILD_DIRECTORY).resolve(filename);
        if (!Files.isRegularFile(source)) {
            throw new RegistryConflictException(
                    "Aiken build artifacts are missing; run scripts/registry/build.sh first"
            );
        }
        Path target = config.runtimeDir().resolve(SCRIPTS_DIRECTORY).resolve(filename).toAbsolutePath();
        try {
            // Create missing parent directories
            Files.createDirectories(target.getParent());
            // Refresh existing runtime copy with the current Aiken output
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to stage registry script for cardano-cli: " + source, ex);
        }
    }

    private Path requireStagedScript(String filename) {
        Path script = config.runtimeDir().resolve(SCRIPTS_DIRECTORY).resolve(filename).toAbsolutePath();
        if (!Files.isRegularFile(script)) {
            throw new RegistryConflictException(
                    "Staged registry script is missing; deploy the registry first: " + script
            );
        }
        return script;
    }

    private void requireCommandExecution() {
        try {
            commandRunner.requireCommandExecution();
        } catch (CardanoCliUnavailableException ex) {
            throw new RegistryConflictException(ex.getMessage());
        }
    }

}
