package org.pubsub.prototype.registry.topic.cardano;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.cli.CacheOnlyCardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardanoRegistryDeploymentManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void cachesDeploymentUntilANewManagerIsCreated() {
        CardanoRegistryConfig config = new CardanoRegistryConfig(
                tempDir.resolve("runtime/registry"),
                "registry-deployer",
                CardanoCliBackend.CACHE_ONLY
        );
        writeDeployment(config, "address-1", "policy-1");
        CardanoRegistryDeploymentManager manager = new CardanoRegistryDeploymentManager(
                config,
                new CacheOnlyCardanoCommandRunner()
        );

        assertEquals(new RegistryDeployment("address-1", "policy-1"), manager.read());
        writeDeployment(config, "address-2", "policy-2");
        assertEquals(new RegistryDeployment("address-1", "policy-1"), manager.read());

        CardanoRegistryDeploymentManager restarted = new CardanoRegistryDeploymentManager(
                config,
                new CacheOnlyCardanoCommandRunner()
        );
        assertEquals(new RegistryDeployment("address-2", "policy-2"), restarted.read());
    }

    private static void writeDeployment(CardanoRegistryConfig config, String address, String policy) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CardanoRegistryEnvironment.TOPIC_REGISTRY_VALIDATOR_ADDRESS, address);
        values.put(CardanoRegistryEnvironment.TOPIC_POLICY_ID, policy);
        CardanoEnvironmentFile.write(config.deploymentFile(), values);
    }
}
