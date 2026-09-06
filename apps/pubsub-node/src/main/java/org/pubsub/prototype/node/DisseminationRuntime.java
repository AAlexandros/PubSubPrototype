package org.pubsub.prototype.node;

import org.pubsub.prototype.dissemination.DisseminationEngine;
import org.pubsub.prototype.dissemination.DisseminationPeerDescriptor;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.protocol.MessageType;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.transport.PubSubTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Bridges Hybrid Dissemination state to Navigation, Netty, subscriptions, and registry lifecycle. */
final class DisseminationRuntime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DisseminationRuntime.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dissemination");
        thread.setDaemon(true);
        return thread;
    });
    private final DisseminationEngine engine;
    private final NavigationRuntime navigation;
    private final Supplier<List<String>> activeTopicsSupplier;
    private final long interval;
    private final String nodeId;
    private PubSubTransport transport;

    DisseminationRuntime(String nodeId, String host, int port, NodeConfig.DisseminationSection config,
                         NavigationRuntime navigation, Supplier<List<String>> activeTopicsSupplier) {
        this.nodeId = nodeId;
        this.navigation = navigation;
        this.activeTopicsSupplier = activeTopicsSupplier;
        this.interval = config.cycleIntervalMs;
        this.engine = new DisseminationEngine(nodeId, host, port, config.randomLinkCount,
                config.staleAfterMs, new Random(config.randomSeed), LOG::info);
        long now = System.currentTimeMillis();
        navigation.engine().subscriptions().forEach(topicId ->
                engine.subscribe(topicId, navigation.engine().peersForTopic(topicId), now));
    }

    DisseminationEngine engine() {
        return engine;
    }

    void attach(PubSubTransport transport) {
        this.transport = transport;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    void subscribe(String topicId) {
        engine.subscribe(topicId, navigation.engine().peersForTopic(topicId), System.currentTimeMillis());
    }

    void unsubscribe(String topicId) {
        engine.unsubscribe(topicId);
    }

    void messageReceived(NodeId peer, ProtocolMessage message) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                String topicId = message.dissemination().topicId();
                var candidates = navigation.engine().peersForTopic(topicId);
                if (message.type() == MessageType.DISSEMINATION_REQUEST) {
                    var response = engine.request(peer.value(), message.dissemination(), candidates, now);
                    transport.replySampling(peer, ProtocolMessage.disseminationGossip(
                            MessageType.DISSEMINATION_RESPONSE, message.requestId(), response));
                } else {
                    engine.response(peer.value(), message.dissemination(), candidates, now);
                }
            } catch (RuntimeException ex) {
                LOG.warn("DISSEMINATION_EXCHANGE_REJECTED nodeId={} peerNodeId={} reason={}",
                        nodeId, peer.value(), ex.getMessage());
            }
        });
    }

    void peerDisconnected(NodeId peer) {
        if (executor.isShutdown()) return;
        try {
            executor.execute(() -> engine.peerUnavailable(peer.value()));
        } catch (RejectedExecutionException ignored) {
            // Shutdown may race a final Netty disconnect callback.
        }
    }

    void disseminate(EventEnvelope event, NodeId source) {
        String sourceId = source == null ? null : source.value();
        for (DisseminationPeerDescriptor peer : engine.forwardingTargets(event.topicId(), sourceId)) {
            transport.sendEvent(new PeerDescriptor(peer.nodeId(), peer.host(), peer.port()), event);
            LOG.info("EVENT_DISSEMINATED topicId={} nodeId={} peerNodeId={} eventId={}",
                    event.topicId(), nodeId, peer.nodeId(), event.eventId());
        }
    }

    private void runCycle() {
        try {
            Set<String> active = new HashSet<>(activeTopicsSupplier.get());
            engine.retainActiveTopics(active);
            long now = System.currentTimeMillis();
            Set<String> current = engine.subscriptions();
            navigation.engine().subscriptions().stream()
                    .filter(active::contains).filter(topicId -> !current.contains(topicId)).forEach(topicId ->
                    engine.subscribe(topicId, navigation.engine().peersForTopic(topicId), now));
            engine.cycle(now, navigation.engine()::peersForTopic).forEach(out ->
                    transport.sendSampling(new PeerDescriptor(
                                    out.peer().nodeId(), out.peer().host(), out.peer().port()),
                            ProtocolMessage.disseminationGossip(
                                    MessageType.DISSEMINATION_REQUEST, out.requestId(), out.exchange())));
        } catch (RuntimeException ex) {
            LOG.error("DISSEMINATION_CYCLE reason=runtime_error", ex);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
