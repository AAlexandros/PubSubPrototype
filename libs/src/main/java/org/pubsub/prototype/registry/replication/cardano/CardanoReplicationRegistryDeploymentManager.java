package org.pubsub.prototype.registry.replication.cardano;

import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stages the replication validator and reads or creates its deployment metadata. */
public final class CardanoReplicationRegistryDeploymentManager {
    private static final String SCRIPT_NAME = "replication-registry.plutus.json";
    private static final Path BUILD_DIRECTORY = Path.of(
            "contracts", "replication-registry", "build", "replication-registry.plutus.json");

    private final CardanoReplicationRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private ReplicationRegistryDeployment cached;

    public CardanoReplicationRegistryDeploymentManager(
            CardanoReplicationRegistryConfig config,
            CardanoCommandRunner commandRunner
    ) {
        this.config = config;
        this.commandRunner = commandRunner;
    }

    public synchronized ReplicationRegistryDeployment initialize() {
        commandRunner.requireCommandExecution();
        Path script = stageScript();
        String address = commandRunner.run("address", "build", "--payment-script-file", script.toString(),
                "--testnet-magic", commandRunner.networkMagic()).trim();
        cached = new ReplicationRegistryDeployment(address);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReplicationRegistryEnvironment.VALIDATOR_ADDRESS, address);
        values.put(ReplicationRegistryEnvironment.SCRIPT_FILE, script.toString());
        values.put(ReplicationRegistryEnvironment.STATE_FILE, config.stateFile().toString());
        CardanoEnvironmentFile.write(config.deploymentFile(), values);
        return cached;
    }

    public synchronized ReplicationRegistryDeployment read() {
        if (cached != null) return cached;
        if (!Files.exists(config.deploymentFile())) return initialize();
        Map<String, String> values = CardanoEnvironmentFile.readRequired(
                config.deploymentFile(), ReplicationRegistryEnvironment.VALIDATOR_ADDRESS);
        cached = new ReplicationRegistryDeployment(values.get(ReplicationRegistryEnvironment.VALIDATOR_ADDRESS));
        return cached;
    }

    public Path validatorScript() {
        Path script = config.runtimeDir().resolve("scripts").resolve(SCRIPT_NAME).toAbsolutePath();
        if (!Files.isRegularFile(script)) throw new IllegalStateException("Replication validator is not staged: " + script);
        return script;
    }

    private Path stageScript() {
        Path source = config.projectRoot().resolve(BUILD_DIRECTORY);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Replication validator build artifact is missing; run scripts/replication/build.sh");
        }
        Path target = config.runtimeDir().resolve("scripts").resolve(SCRIPT_NAME).toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to stage replication validator " + source, ex);
        }
    }
}
