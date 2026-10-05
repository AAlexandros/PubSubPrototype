package org.pubsub.prototype.node.bootstrap;

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
import java.util.concurrent.CountDownLatch;

/**
 * Owns construction and lifecycle for one Pub/Sub node process.
 *
 * <p>{@link #runUntilShutdown()} waits on the calling thread while network, HTTP, and scheduled
 * work run on their own threads. The shutdown hook calls {@link #close()}, which stops components
 * and releases the wait; repeated close calls are safe.</p>
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
        // Initialize the event coordinator
        NodeEventCoordinator eventService = new NodeEventCoordinator(identity, config.identityPath(), registry);
        // Initialize the persistence runtime
        PersistenceRuntime persistence = createPersistence(config, registry);
        eventService.attachPersistence(persistence);

        // Initialize the node endpoint
        NodeEndpoint self = new NodeEndpoint(identity.nodeId().value(), config.sampling().advertisedHost,
                config.node().listenPort);
        // Initialize the peer sampling runtime (Layer one)
        PeerSamplingRuntime sampling = createSampling(config, self);
        // Initialize the navigation runtime (Layer two)
        NavigationRuntime navigation = createNavigation(config, identity, registry, sampling);
        // Initialize the dissemination runtime (The actual dissemination layer)
        DisseminationRuntime dissemination = createDissemination(config, identity, registry, navigation);

        // The pub-sub transport used to transmit the various messages between the nodes
        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity, self,
                new NodeTransportListener(sampling, navigation, dissemination, eventService));
        // Attach the transport to all the runtimes
        sampling.attach(transport);
        navigation.attach(transport);
        dissemination.attach(transport);
        eventService.attachSender(transport);
        eventService.attachDissemination(dissemination);

        // External control server for managing and monitoring the node
        // ToDo: Conside making each node autonomus, completely removing the HTTP dependency
        EventControlServer controlServer = new EventControlServer(
                config.controlHost(), config.controlPort(), eventService);
        controlServer.addSampling(sampling.sampling(), identity.nodeId().value(),
                config.sampling().viewSize);
        controlServer.addNavigation(navigation, identity.nodeId().value());
        controlServer.addDissemination(dissemination, identity.nodeId().value());
        controlServer.addPersistence(persistence);

        return new NodeApplication(registry, persistence, sampling, navigation,
                dissemination, transport, controlServer);
    }

    public void runUntilShutdown() throws InterruptedException {
        Thread shutdownHook = new Thread(this::close, "shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            start();
            stopped.await();
        // finally block to cover all the edge cases that the start may fail
        } finally {
            close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down and is running the hook.
            }
        }
    }

    /**
     * Start all the node runtimes.
     * @throws InterruptedException
     */
    public void start() throws InterruptedException {
        registry.start();
        controlServer.start();
        transport.start();
        sampling.start();
        navigation.start();
        dissemination.start();
    }

    @Override
    public void close() {
        try {
            controlServer.close();
            registry.close();
            sampling.close();
            navigation.close();
            dissemination.close();
            persistence.close();
            transport.close();
        } finally {
            stopped.countDown();
        }
    }

    private static RegistrySynchronizer createRegistry(NodeConfig config) {
        return new RegistrySynchronizer(
                new CardanoTopicRegistry(new CardanoRegistryConfig(
                        Path.of(config.registry().runtimeDir),
                        config.registry().signer,
                        config.registry().cliBackend)),
                config.registry().pollIntervalMs);
    }

    // Runtime creation helpers

    private static PersistenceRuntime createPersistence(NodeConfig config, RegistrySynchronizer registry) {
        NodeConfig.PersistenceSection persistence = config.persistence();
        return new PersistenceRuntime(new PersistenceRuntime.Settings(
                Path.of(persistence.membershipPath),
                Path.of(persistence.deliveryStatePath),
                Duration.ofMillis(persistence.connectionTimeoutMs),
                Duration.ofMillis(persistence.requestTimeoutMs),
                persistence.retries,
                persistence.recoveryConcurrency), registry);
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
                registry::activeTopicIds,
                () -> sampling.sampling().view());
    }

    private static DisseminationRuntime createDissemination(NodeConfig config, NodeIdentity identity,
                                                            RegistrySynchronizer registry,
                                                            NavigationRuntime navigation) {
        NodeConfig.DisseminationSection dissemination = config.dissemination();
        return new DisseminationRuntime(
                identity.nodeId().value(), config.sampling().advertisedHost,
                config.node().listenPort,
                new DisseminationRuntime.Settings(dissemination.randomLinkCount,
                        dissemination.cycleIntervalMs, dissemination.staleAfterMs,
                        dissemination.randomSeed),
                navigation,
                registry::activeTopicIds);
    }
}
