package org.pubsub.prototype.node;

import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.transport.PubSubTransport;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class PubSubNodeMain {
    private PubSubNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: pubsub-node <config.yaml>");
            System.exit(2);
        }

        NodeConfig config = NodeConfigLoader.load(Path.of(args[0]));
        NodeIdentity identity = IdentityStore.loadOrCreate(config.identityPath());
        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity, null);
        RegistrySynchronizer registrySynchronizer = null;
        if (config.registryEnabled()) {
            registrySynchronizer = new RegistrySynchronizer(
                    new CardanoTopicRegistry(new CardanoRegistryConfig(
                            Path.of(config.registry().runtimeDir),
                            config.registry().signer
                    )),
                    config.registry().pollIntervalMs
            );
            registrySynchronizer.start();
        }
        RegistrySynchronizer finalRegistrySynchronizer = registrySynchronizer;
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (finalRegistrySynchronizer != null) {
                finalRegistrySynchronizer.close();
            }
            transport.close();
            stop.countDown();
        }, "shutdown"));
        transport.start();
        stop.await();
    }
}
