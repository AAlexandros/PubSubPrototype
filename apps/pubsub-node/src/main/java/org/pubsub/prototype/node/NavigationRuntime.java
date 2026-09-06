package org.pubsub.prototype.node;

import org.pubsub.prototype.navigation.NavigationEngine;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.navigation.TopicOrdering;
import org.pubsub.prototype.protocol.MessageType;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.transport.PubSubTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bridges the transport-free Vicinity navigation engine to the Netty runtime and Cardano registry. */
final class NavigationRuntime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NavigationRuntime.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "navigation");
        thread.setDaemon(true);
        return thread;
    });
    private final NavigationEngine engine;
    private final SubscriptionStore subscriptions;
    private final Supplier<List<String>> activeTopicsSupplier;
    private final Supplier<List<PeerDescriptor>> samplingViewSupplier;
    private final long interval;
    private final String nodeId;
    private PubSubTransport transport;
    private List<String> lastActiveTopics = List.of();

    NavigationRuntime(String nodeId, String host, int port, SubscriptionStore subscriptions,
                       NodeConfig.NavigationSection config,
                       Supplier<List<String>> activeTopicsSupplier,
                       Supplier<List<PeerDescriptor>> samplingViewSupplier) {
        this.nodeId = nodeId;
        this.subscriptions = subscriptions;
        this.activeTopicsSupplier = activeTopicsSupplier;
        this.samplingViewSupplier = samplingViewSupplier;
        this.interval = config.cycleIntervalMs;
        this.engine = new NavigationEngine(nodeId, host, port, subscriptions.snapshot(),
                config.capacity, config.routingBase, config.staleAfterMs, new Random(), LOG::info);
    }

    NavigationEngine engine() {
        return engine;
    }

    void attach(PubSubTransport transport) {
        this.transport = transport;
    }

    void start() {
        LOG.info("NAVIGATION_STARTED nodeId={} subscriptions={}", nodeId, subscriptions.snapshot());
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    void subscribe(String topicId) {
        subscriptions.add(topicId);
        engine.subscribe(topicId);
    }

    void unsubscribe(String topicId) {
        subscriptions.remove(topicId);
        engine.unsubscribe(topicId);
    }

    void messageReceived(NodeId peer, ProtocolMessage message) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                if (message.type() == MessageType.NAVIGATION_REQUEST) {
                    var response = engine.request(peer.value(), message.navigation(), now);
                    transport.replySampling(peer, ProtocolMessage.navigationGossip(MessageType.NAVIGATION_RESPONSE, message.requestId(), response));
                } else {
                    engine.response(peer.value(), message.navigation(), now);
                }
            } catch (RuntimeException ex) {
                LOG.warn("NAVIGATION_EXCHANGE_REJECTED nodeId={} peerNodeId={} reason={}", nodeId, peer.value(), ex.getMessage());
            }
        });
    }

    private void runCycle() {
        try {
            List<String> active = activeTopicsSupplier.get();
            if (!active.equals(lastActiveTopics)) {
                lastActiveTopics = active;
                engine.updateOrdering(TopicOrdering.of(active));
            }
            long now = System.currentTimeMillis();
            for (PeerDescriptor sample : samplingViewSupplier.get()) {
                engine.ingestSample(sample.nodeId(), sample.host(), sample.port(), now);
            }
            engine.cycle(now).ifPresent(out -> transport.sendSampling(
                    new PeerDescriptor(out.peer().nodeId(), out.peer().host(), out.peer().port()),
                    ProtocolMessage.navigationGossip(MessageType.NAVIGATION_REQUEST, out.requestId(), out.exchange())));
        } catch (RuntimeException ex) {
            LOG.error("NAVIGATION_CYCLE reason=runtime_error", ex);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
