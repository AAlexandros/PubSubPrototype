package org.pubsub.prototype.node;

import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.transport.PubSubTransport;
import org.pubsub.prototype.sampling.PeerDescriptor;

import java.nio.file.Path;
import java.util.List;
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
        PeerSamplingRuntime sampling = config.sampling() == null ? null : new PeerSamplingRuntime(
                new PeerDescriptor(identity.nodeId().value(),
                        config.sampling().advertisedHost, config.node().listenPort), config.sampling(), eventService);
        RegistrySynchronizer finalRegistrySynchronizerForTopics = registrySynchronizer;
        PeerSamplingRuntime finalSamplingForView = sampling;
        NavigationRuntime navigation = config.navigation() == null ? null : new NavigationRuntime(
                identity.nodeId().value(),
                config.sampling() != null ? config.sampling().advertisedHost : config.node().listenHost,
                config.node().listenPort,
                SubscriptionStore.loadOrCreate(config.subscriptionsPath()),
                config.navigation(),
                finalRegistrySynchronizerForTopics == null ? List::of : finalRegistrySynchronizerForTopics::activeTopicIds,
                finalSamplingForView == null ? List::of : () -> finalSamplingForView.sampling().view());
        DisseminationRuntime dissemination = config.dissemination() == null ? null : new DisseminationRuntime(
                identity.nodeId().value(), config.sampling().advertisedHost, config.node().listenPort,
                config.dissemination(), navigation,
                finalRegistrySynchronizerForTopics == null ? List::of : finalRegistrySynchronizerForTopics::activeTopicIds);
        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity,
                new NodeTransportListener(sampling, navigation, dissemination, eventService));
        if (sampling != null) sampling.attach(transport);
        if (navigation != null) navigation.attach(transport);
        if (dissemination != null) dissemination.attach(transport);
        eventService.attachTransport(transport);
        eventService.attachDissemination(dissemination);
        EventControlServer controlServer = new EventControlServer(config.controlHost(), config.controlPort(), eventService);
        if (sampling != null) controlServer.addSampling(sampling.sampling(), identity.nodeId().value(), config.sampling().viewSize);
        if (navigation != null) controlServer.addNavigation(navigation, identity.nodeId().value());
        if (dissemination != null) controlServer.addDissemination(dissemination, identity.nodeId().value());
        controlServer.start();
        RegistrySynchronizer finalRegistrySynchronizer = registrySynchronizer;
        EventControlServer finalControlServer = controlServer;
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            finalControlServer.close();
            if (finalRegistrySynchronizer != null) {
                finalRegistrySynchronizer.close();
            }
            if (sampling != null) sampling.close();
            if (navigation != null) navigation.close();
            if (dissemination != null) dissemination.close();
            transport.close();
            stop.countDown();
        }, "shutdown"));
        transport.start();
        if (sampling != null) sampling.start();
        if (navigation != null) navigation.start();
        if (dissemination != null) dissemination.start();
        stop.await();
    }
}
