package org.pubsub.prototype.node;

import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.event.TopicStateProvider;
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
        TopicStateProvider topics = registrySynchronizer == null ? topicId -> java.util.Optional.empty() : registrySynchronizer;
        NodeEventService eventService = new NodeEventService(identity, config.identityPath(), topics);
        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity, eventService);
        eventService.attachTransport(transport);
        EventControlServer controlServer = new EventControlServer(config.controlHost(), config.controlPort(), eventService);
        controlServer.start();
        RegistrySynchronizer finalRegistrySynchronizer = registrySynchronizer;
        EventControlServer finalControlServer = controlServer;
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            finalControlServer.close();
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
