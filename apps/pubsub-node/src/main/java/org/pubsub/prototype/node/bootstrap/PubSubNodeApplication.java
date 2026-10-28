package org.pubsub.prototype.node.bootstrap;

import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.node.adapter.http.NodeControlHttpAdapter;
import org.pubsub.prototype.node.adapter.transport.NodeTransportListener;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.service.DisseminationService;
import org.pubsub.prototype.node.service.NavigationService;
import org.pubsub.prototype.node.service.PeerSamplingProtocolService;
import org.pubsub.prototype.node.service.EventStorageService;
import org.pubsub.prototype.node.service.TopicRegistrySyncService;
import org.pubsub.prototype.node.service.EventProcessingService;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.event.EventDeduplicator;
import org.pubsub.prototype.event.EventValidator;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.transport.PubSubTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Owns construction and lifecycle for one Pub/Sub node process.
 *
 * <p>
 * {@link #runUntilShutdown()} waits on the calling thread while network, HTTP,
 * and scheduled
 * work run on their own threads. The shutdown hook calls {@link #close()},
 * which stops components
 * and releases the wait; repeated close calls are safe.
 * </p>
 */
public final class PubSubNodeApplication implements AutoCloseable {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(PubSubNodeApplication.class);

    // Services
    private final TopicRegistrySyncService registry;
    private final EventStorageService eventStorage;
    private final PeerSamplingProtocolService sampling;
    private final NavigationService navigation;
    private final DisseminationService dissemination;

    private final PubSubTransport transport;
    private final CountDownLatch stopped = new CountDownLatch(1);

    // Creates an HTTP API for sending commands to this node and inspecting its
    // state.
    // The testbed exposes this port on the host's loopback interface.
    private final NodeControlHttpAdapter controlAdapter;

    private PubSubNodeApplication(TopicRegistrySyncService registry,
            EventStorageService eventStorage,
            PeerSamplingProtocolService sampling, NavigationService navigation,
            DisseminationService dissemination, PubSubTransport transport,
            NodeControlHttpAdapter controlAdapter) {
        this.registry = registry;
        this.eventStorage = eventStorage;
        this.sampling = sampling;
        this.navigation = navigation;
        this.dissemination = dissemination;
        this.transport = transport;
        this.controlAdapter = controlAdapter;
    }

    public static PubSubNodeApplication create(NodeConfig config) throws IOException {
        NodeLog.init(LOG, "BOOTSTRAP_STARTED p2p={}:{} control={}:{} seeds={}",
                config.node().listenHost, config.node().listenPort, config.controlHost(),
                config.controlPort(), config.peers().size());
        // Initialize the identity of the node, which is shared among:
        // EventProcessingService uses the key pair to sign events.
        // PeerSamplingProtocolService — uses its nodeId.
        // NavigationService — uses its nodeId.
        // DisseminationService — uses its nodeId.
        // PubSubTransport — uses the identity for authenticated peer communication.
        // NodeControlHttpAdapter — exposes node information using that nodeId.
        NodeIdentity identity = IdentityStore.loadOrCreate(config.identityPath());
        NodeLog.init(LOG, "IDENTITY_READY id={} directory={}",
                NodeLog.node(identity.nodeId().value()), config.identityPath());

        // Initialize the topic registry sync service.
        TopicRegistrySyncService registry = createRegistry(config);

        // Initialize the event services
        EventValidator eventValidator = new EventValidator(registry, new EventDeduplicator(10_000));
        EventProcessingService eventService = new EventProcessingService(identity, config.identityPath(),
                eventValidator);
        // Initialize event storage and recovery.
        EventStorageService eventStorage = new EventStorageService(config.persistence(), registry);
        eventService.attachEventStorage(eventStorage);

        // Initialize the protocol services
        NodeEndpoint self = new NodeEndpoint(identity.nodeId().value(), config.sampling().advertisedHost,
                config.node().listenPort);
        // Initialize the peer sampling protocol service (Layer one).
        PeerSamplingProtocolService sampling = new PeerSamplingProtocolService(self, config.sampling());
        // Initialize the navigation service (Layer two).
        NavigationService navigation = createNavigation(config, identity, registry, sampling);
        // Initialize the dissemination service.
        DisseminationService dissemination = new DisseminationService(
                identity.nodeId().value(), config.sampling().advertisedHost, config.node().listenPort,
                config.dissemination(), navigation, registry::activeTopicIds);

        // The pub-sub transport used to transmit the various messages between the nodes
        PubSubTransport transport = new PubSubTransport(config.toTransportConfig(), identity, self,
                new NodeTransportListener(sampling, navigation, dissemination, eventService, eventValidator));
        // Attach the transport to the protocol services.
        sampling.attach(transport);
        navigation.attach(transport);
        dissemination.attach(transport);
        eventService.attachDissemination(dissemination);

        NodeControlHttpAdapter controlAdapter = new NodeControlHttpAdapter(
                config.controlHost(), config.controlPort(), eventService, sampling.sampling(), navigation,
                dissemination, eventStorage, identity.nodeId().value(), config.sampling().viewSize);

        NodeLog.init(LOG, "COMPONENTS_WIRED");
        return new PubSubNodeApplication(registry, eventStorage, sampling, navigation,
                dissemination, transport, controlAdapter);
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
     * Start the node services, adapters, and transport.
     * 
     * @throws InterruptedException
     */
    public void start() throws InterruptedException {
        registry.start();
        controlAdapter.start();
        transport.start();
        sampling.start();
        navigation.start();
        dissemination.start();
        NodeLog.init(LOG, "NODE_READY");
    }

    @Override
    public void close() {
        try {
            controlAdapter.close();
            registry.close();
            sampling.close();
            navigation.close();
            dissemination.close();
            eventStorage.close();
            transport.close();
        } finally {
            stopped.countDown();
        }
    }

    private static TopicRegistrySyncService createRegistry(NodeConfig config) {
        return new TopicRegistrySyncService(
                new CardanoTopicRegistry(new CardanoRegistryConfig(
                        Path.of(config.registry().runtimeDir),
                        config.registry().signer,
                        config.registry().cliBackend)),
                config.registry().pollIntervalMs);
    }

    // Service construction helpers

    private static NavigationService createNavigation(NodeConfig config, NodeIdentity identity,
            TopicRegistrySyncService registry,
            PeerSamplingProtocolService sampling) {
        return new NavigationService(
                identity.nodeId().value(), config.sampling().advertisedHost, config.node().listenPort,
                SubscriptionStore.loadOrCreate(config.subscriptionsPath()),
                config.navigation(),
                registry::activeTopicIds,
                () -> sampling.sampling().view());
    }

}
