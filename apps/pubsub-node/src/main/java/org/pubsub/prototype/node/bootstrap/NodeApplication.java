package org.pubsub.prototype.node.bootstrap;

import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.node.adapter.http.EventControlServer;
import org.pubsub.prototype.node.adapter.transport.NodeTransportListener;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.runtimes.DisseminationRuntime;
import org.pubsub.prototype.node.runtimes.NavigationRuntime;
import org.pubsub.prototype.node.runtimes.PeerSamplingRuntime;
import org.pubsub.prototype.node.runtimes.PersistenceRuntime;
import org.pubsub.prototype.node.runtimes.RegistrySynchronizer;
import org.pubsub.prototype.node.service.NodeEventCoordinator;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.transport.PubSubTransport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.empty;

/**
 * Owns construction and lifecycle for one Pub/Sub node process.
 */
public final class NodeApplication implements AutoCloseable {
    private final RegistrySynchronizer registry;
    private final PersistenceRuntime persistence;
    private final PeerSamplingRuntime sampling;
    private final NavigationRuntime navigation;
    private final DisseminationRuntime dissemination;
    private final PubSubTransport transport;
    private final EventControlServer controlServer;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private NodeApplication(RegistrySynchronizer registry, PersistenceRuntime persistence,
                            PeerSamplingRuntime sampling, NavigationRuntime navigation,
                            DisseminationRuntime dissemination, PubSubTransport transport,
                            EventControlServer controlServer) {
        this.registry = registry;
        this.persistence = persistence;
        this.sampling = sampling;
        this.navigation = navigation;
        this.dissemination = dissemination;
        this.transport = transport;
        this.controlServer = controlServer;
    }

    public static NodeApplication create(NodeConfig config) throws IOException {
        // Initialize the identity of the node, which is shared among:
        // NodeEventService — uses the key pair to sign events.
        // PeerSamplingRuntime — uses its nodeId.
        // NavigationRuntime — uses its nodeId.
        // DisseminationRuntime — uses its nodeId.
        // PubSubTransport — uses the identity for authenticated peer communication.
        // EventControlServer — exposes runtime information using that nodeId.
        NodeIdentity identity = IdentityStore.loadOrCreate(config.identityPath());
        // Initialize the topic registry synchronizer
        RegistrySynchronizer registry = createRegistry(config);
        TopicStateProvider topics = registry == null ? topicId -> empty() : registry;
        NodeEventCoordinator eventService = new NodeEventCoordinator(identity, config.identityPath(), topics);

        PersistenceRuntime persistence = createPersistence(config, topics);
        if (persistence != null) {
            persistence.attachDelivery(eventService::acceptRecovered);
            eventService.attachPersistence(persistence);
        }

        PeerSamplingRuntime sampling = null;
        NavigationRuntime navigation = null;
        NodeEndpoint self = null;
        if (config.sampling() != null) {
            self = new NodeEndpoint(identity.nodeId().value(), config.sampling().advertisedHost,
                    config.node().listenPort);
            sampling = createSampling(config, self);
            navigation = createNavigation(config, identity, registry, sampling);
        }
        DisseminationRuntime dissemination = createDissemination(config, identity, registry, navigation);

        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity, self,
                new NodeTransportListener(sampling, navigation, dissemination, eventService));
        if (sampling != null) {
            sampling.attach(transport);
            navigation.attach(transport);
        }
        if (dissemination != null) dissemination.attach(transport);
        eventService.attachTransport(transport);
        eventService.attachDissemination(dissemination);

        EventControlServer controlServer = new EventControlServer(
                config.controlHost(), config.controlPort(), eventService);
        if (sampling != null) {
            controlServer.addSampling(sampling.sampling(), identity.nodeId().value(),
                    config.sampling().viewSize);
            controlServer.addNavigation(navigation, identity.nodeId().value());
        }
        if (dissemination != null) controlServer.addDissemination(dissemination, identity.nodeId().value());
        if (persistence != null) controlServer.addPersistence(persistence);

        return new NodeApplication(registry, persistence, sampling, navigation,
                dissemination, transport, controlServer);
    }

    public void runUntilShutdown() throws InterruptedException {
        Thread shutdownHook = new Thread(this::close, "shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            start();
            stopped.await();
        } finally {
            close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down and is running the hook.
            }
        }
    }

    public void start() throws InterruptedException {
        if (!started.compareAndSet(false, true)) return;
        if (registry != null) registry.start();
        controlServer.start();
        transport.start();
        if (sampling != null) {
            sampling.start();
            navigation.start();
        }
        if (dissemination != null) dissemination.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            controlServer.close();
            if (registry != null) registry.close();
            if (sampling != null) {
                sampling.close();
                navigation.close();
            }
            if (dissemination != null) dissemination.close();
            if (persistence != null) persistence.close();
            transport.close();
        } finally {
            stopped.countDown();
        }
    }

    private static RegistrySynchronizer createRegistry(NodeConfig config) {
        if (!config.registryEnabled()) return null;
        return new RegistrySynchronizer(
                new CardanoTopicRegistry(new CardanoRegistryConfig(
                        Path.of(config.registry().runtimeDir), config.registry().signer)),
                config.registry().pollIntervalMs);
    }

    // Runtime creation helpers

    private static PersistenceRuntime createPersistence(NodeConfig config, TopicStateProvider topics) {
        NodeConfig.PersistenceSection persistence = config.persistence();
        if (persistence == null || !persistence.enabled) return null;
        return new PersistenceRuntime(new PersistenceRuntime.Settings(
                Path.of(persistence.membershipPath),
                Path.of(persistence.deliveryStatePath),
                Duration.ofMillis(persistence.connectionTimeoutMs),
                Duration.ofMillis(persistence.requestTimeoutMs),
                persistence.retries,
                persistence.recoveryConcurrency), topics);
    }

    private static PeerSamplingRuntime createSampling(NodeConfig config, NodeEndpoint self) {
        NodeConfig.SamplingSection sampling = config.sampling();
        return new PeerSamplingRuntime(
                self,
                new PeerSamplingRuntime.Settings(sampling.viewSize, sampling.swapLength,
                        sampling.cycleIntervalMs, sampling.ageThreshold, sampling.proofFanout,
                        sampling.randomSeed));
    }

    private static NavigationRuntime createNavigation(NodeConfig config, NodeIdentity identity,
                                                      RegistrySynchronizer registry,
                                                      PeerSamplingRuntime sampling) {
        NodeConfig.NavigationSection navigation = config.navigation();
        return new NavigationRuntime(
                identity.nodeId().value(), config.sampling().advertisedHost, config.node().listenPort,
                SubscriptionStore.loadOrCreate(config.subscriptionsPath()),
                new NavigationRuntime.Settings(navigation.capacity, navigation.routingBase,
                        navigation.cycleIntervalMs, navigation.staleAfterMs),
                registry == null ? List::of : registry::activeTopicIds,
                () -> sampling.sampling().view());
    }

    private static DisseminationRuntime createDissemination(NodeConfig config, NodeIdentity identity,
                                                            RegistrySynchronizer registry,
                                                            NavigationRuntime navigation) {
        NodeConfig.DisseminationSection dissemination = config.dissemination();
        if (dissemination == null) return null;
        return new DisseminationRuntime(
                identity.nodeId().value(), config.sampling().advertisedHost,
                config.node().listenPort,
                new DisseminationRuntime.Settings(dissemination.randomLinkCount,
                        dissemination.cycleIntervalMs, dissemination.staleAfterMs,
                        dissemination.randomSeed),
                navigation,
                registry == null ? List::of : registry::activeTopicIds);
    }
}
