package org.pubsub.prototype.node.service;

import org.pubsub.prototype.dissemination.DisseminationEngine;
import org.pubsub.prototype.dissemination.DisseminationPeerDescriptor;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.protocol.MessageType;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.transport.TransportSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Maintains topic-specific forwarding peers through Dissemination gossip.
 * It uses Navigation candidates and active-topic state to update the overlay,
 * then sends live events to selected peers while excluding the source peer.
 */
public final class DisseminationService implements CyclicTransportService {

    // Standard logger
    private static final Logger LOG = LoggerFactory.getLogger(DisseminationService.class);

    private final ScheduledExecutorService executor =
            AsyncUtil.scheduledSingleThread("dissemination");
    private final DisseminationEngine engine;
    private final NavigationService navigationService;
    private final Supplier<List<String>> activeTopicsSupplier;
    private final long interval;
    private final String nodeId;
    private TransportSender sender;

    public DisseminationService(String nodeId, String host, int port, NodeConfig.DisseminationSection config,
                                NavigationService navigation, Supplier<List<String>> activeTopicsSupplier) {
        this.nodeId = nodeId;
        this.navigationService = navigation;
        this.activeTopicsSupplier = activeTopicsSupplier;
        this.interval = config.cycleIntervalMs;
        this.engine = new DisseminationEngine(nodeId, host, port, config.randomLinkCount,
                config.staleAfterMs, new Random(config.randomSeed), LOG::info);
        long now = System.currentTimeMillis();
        navigation.navigation().subscriptions().forEach(topicId ->
                engine.subscribe(topicId, navigation.navigation().peersForTopic(topicId), now));
        NodeLog.init(LOG, "DISSEMINATION_CONFIGURED advertised={}:{} randomLinks={} cycleMs={} staleAfterMs={} subscriptions={}",
                host, port, config.randomLinkCount, interval, config.staleAfterMs, engine.subscriptions().size());
    }

    public DisseminationEngine engine() {
        return engine;
    }

    @Override
    public void attach(TransportSender sender) {
        this.sender = sender;
    }

    @Override
    public void start() {
        NodeLog.init(LOG, "DISSEMINATION_STARTED cycleMs={}", interval);
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void subscribe(String topicId) {
        LOG.info("DISSEMINATION_SUBSCRIBE topic={}", NodeLog.topic(topicId));
        engine.subscribe(topicId, navigationService.navigation().peersForTopic(topicId), System.currentTimeMillis());
        LOG.info("DISSEMINATION_SUBSCRIPTIONS count={}", engine.subscriptions().size());
    }

    public void unsubscribe(String topicId) {
        LOG.info("DISSEMINATION_UNSUBSCRIBE topic={}", NodeLog.topic(topicId));
        engine.unsubscribe(topicId);
        LOG.info("DISSEMINATION_SUBSCRIPTIONS count={}", engine.subscriptions().size());
    }

    @Override
    public void messageReceived(NodeId peer, ProtocolMessage message) {
        NodeLog.network(LOG, "DISSEMINATION_IN peer={} type={} requestId={} topic={} candidates={}",
                NodeLog.node(peer.value()), message.type(), message.requestId(),
                NodeLog.topic(message.dissemination().topicId()), message.dissemination().candidates().size());
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                String topicId = message.dissemination().topicId();
                var candidates = navigationService.navigation().peersForTopic(topicId);
                if (message.type() == MessageType.DISSEMINATION_REQUEST) {
                    var response = engine.request(peer.value(), message.dissemination(), candidates, now);
                    NodeLog.network(LOG, "DISSEMINATION_SEND peer={} type={} requestId={} topic={} candidates={}",
                            NodeLog.node(peer.value()), MessageType.DISSEMINATION_RESPONSE,
                            message.requestId(), NodeLog.topic(topicId), response.candidates().size());
                    sender.replyMessage(peer, ProtocolMessage.disseminationGossip(
                            MessageType.DISSEMINATION_RESPONSE, message.requestId(), response));
                } else {
                    engine.response(peer.value(), message.dissemination(), candidates, now);
                }
                LOG.info("DISSEMINATION_OUT peer={} type={} requestId={} topic={} result=accepted",
                        NodeLog.node(peer.value()), message.type(), message.requestId(), NodeLog.topic(topicId));
            } catch (RuntimeException ex) {
                LOG.warn("DISSEMINATION_EXCHANGE_REJECTED peer={} reason={}",
                        NodeLog.node(peer.value()), ex.getMessage());
            }
        });
    }

    public void peerDisconnected(NodeId peer) {
        LOG.info("DISSEMINATION_PEER_DISCONNECTED peer={}", NodeLog.node(peer.value()));
        if (executor.isShutdown()) return;
        try {
            executor.execute(() -> engine.peerUnavailable(peer.value()));
        } catch (RejectedExecutionException ignored) {
            // Shutdown may race a final Netty disconnect callback.
        }
    }

    /** Sends the envelope to known peers for its topic, excluding the source peer. */
    public void sendEventToTopicPeers(EventEnvelope event, NodeId source) {
        String sourceId = source == null ? null : source.value();
        var targets = engine.forwardingTargets(event.topicId(), sourceId);
        LOG.info("DISSEMINATE_IN eventId={} topic={} source={} targetCount={}",
                event.eventId(), NodeLog.topic(event.topicId()),
                NodeLog.node(sourceId), targets.size());
        for (DisseminationPeerDescriptor peer : targets) {
            sender.sendMessage(new NodeEndpoint(peer.nodeId(), peer.host(), peer.port()), ProtocolMessage.event(event));
            LOG.info("EVENT_DISSEMINATED topicId={} nodeId={} peerNodeId={} eventId={}",
                    event.topicId(), nodeId, peer.nodeId(), event.eventId());
            NodeLog.network(LOG, "EVENT_SENT peer={} eventId={} topic={}",
                    NodeLog.node(peer.nodeId()), event.eventId(), NodeLog.topic(event.topicId()));
        }
        LOG.info("DISSEMINATE_OUT eventId={} sentTo={}", event.eventId(), targets.size());
    }

    /** Broadcasts an event to all connected peers, bypassing topic-based forwarding. */
    public void broadcastEventToConnectedPeers(EventEnvelope event) {
        LOG.info("EVENT_BROADCAST eventId={} topicId={}", event.eventId(), event.topicId());
        sender.broadcastMessage(ProtocolMessage.event(event));
    }

    @Override
    public void runCycle() {
        try {
            Set<String> active = new HashSet<>(activeTopicsSupplier.get());
            LOG.debug("DISSEMINATION_TICK_IN activeTopics={} subscriptions={}", active.size(), engine.subscriptions().size());
            engine.retainActiveTopics(active);
            long now = System.currentTimeMillis();
            Set<String> current = engine.subscriptions();
            navigationService.navigation().subscriptions().stream()
                    .filter(active::contains).filter(topicId -> !current.contains(topicId)).forEach(topicId ->
                    engine.subscribe(topicId, navigationService.navigation().peersForTopic(topicId), now));
            var outgoing = engine.cycle(now, navigationService.navigation()::peersForTopic);
            outgoing.forEach(out -> {
                    NodeLog.network(LOG, "DISSEMINATION_SEND peer={} type={} requestId={} topic={} candidates={}",
                            NodeLog.node(out.peer().nodeId()),
                            MessageType.DISSEMINATION_REQUEST, out.requestId(),
                            NodeLog.topic(out.exchange().topicId()), out.exchange().candidates().size());
                    sender.sendMessage(new NodeEndpoint(
                                    out.peer().nodeId(), out.peer().host(), out.peer().port()),
                            ProtocolMessage.disseminationGossip(
                                    MessageType.DISSEMINATION_REQUEST, out.requestId(), out.exchange()));
            });
            LOG.debug("DISSEMINATION_TICK_OUT requests={}", outgoing.size());
        } catch (RuntimeException ex) {
            LOG.error("DISSEMINATION_CYCLE reason=runtime_error", ex);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

}
