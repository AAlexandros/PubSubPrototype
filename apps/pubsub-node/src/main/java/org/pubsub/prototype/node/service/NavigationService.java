package org.pubsub.prototype.node.service;

import org.pubsub.prototype.navigation.NavigationLayer;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.navigation.TopicIndex;
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

import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Maintains topic-relevant peers through Navigation gossip. It persists local
 * subscriptions and, each cycle, refreshes the active-topic index, ingests
 * SecureCyclon samples, and exchanges candidates through the transport.
 */
public final class NavigationService implements CyclicTransportService {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(NavigationService.class);

    // Runs navigation cycles and incoming messages on one thread
    private final ScheduledExecutorService executor = AsyncUtil.scheduledSingleThread("navigation");

    private final NavigationLayer navigation;
    private final SubscriptionStore subscriptions;
    private final Supplier<List<String>> activeTopicsSupplier;
    private final Supplier<List<NodeEndpoint>> samplingViewSupplier;
    private final long interval;
    private TransportSender sender;
    private List<String> lastActiveTopics = List.of();

    /**
     * @param activeTopicsSupplier method that supplies a fresh snapshot on every
     *                             navigation cycle so
     *                             registry updates affect the topic index; a fixed
     *                             list
     *                             would preserve only the startup snapshot.
     * @param samplingViewSupplier method that supplies the current peer-sampling
     *                             view for each cycle.
     */
    public NavigationService(String nodeId, String host, int port, SubscriptionStore subscriptions,
            NodeConfig.NavigationSection config, Supplier<List<String>> activeTopicsSupplier,
            Supplier<List<NodeEndpoint>> samplingViewSupplier) {
        this.subscriptions = subscriptions;
        this.activeTopicsSupplier = activeTopicsSupplier;
        this.samplingViewSupplier = samplingViewSupplier;
        this.interval = config.cycleIntervalMs;
        this.navigation = new NavigationLayer(nodeId, host, port, subscriptions.snapshot(),
                config.capacity, config.routingBase, config.staleAfterMs, new Random(), LOG::info);
        NodeLog.init(LOG,
                "NAVIGATION_CONFIGURED advertised={}:{} capacity={} routingBase={} cycleMs={} staleAfterMs={} subscriptions={}",
                host, port, config.capacity, config.routingBase, interval,
                config.staleAfterMs, subscriptions.snapshot().size());
    }

    public NavigationLayer navigation() {
        return navigation;
    }

    @Override
    public void attach(TransportSender sender) {
        this.sender = sender;
    }

    @Override
    public void start() {
        NodeLog.init(LOG, "NAVIGATION_STARTED cycleMs={}", interval);
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runCycle() {
        try {
            List<String> active = activeTopicsSupplier.get();
            List<NodeEndpoint> samples = samplingViewSupplier.get();
            if (!active.equals(lastActiveTopics)) {
                LOG.info("NAVIGATION_TOPIC_INDEX_UPDATED topics={}", active.stream().map(NodeLog::topic).toList());
                lastActiveTopics = active;
                navigation.updateTopicIndex(TopicIndex.of(active));
            }
            long now = System.currentTimeMillis();
            for (NodeEndpoint sample : samples) {
                navigation.ingestSample(sample.nodeId(), sample.host(), sample.port(), now);
            }
            navigation.cycle(now).ifPresentOrElse(out -> {
                NodeLog.network(LOG, "NAVIGATION_SEND peer={} type={} requestId={} candidates={}",
                        NodeLog.node(out.peer().nodeId()),
                        MessageType.NAVIGATION_REQUEST, out.requestId(), out.exchange().candidates().size());
                sender.sendMessage(new NodeEndpoint(out.peer().nodeId(), out.peer().host(), out.peer().port()),
                        ProtocolMessage.navigationGossip(MessageType.NAVIGATION_REQUEST, out.requestId(),
                                out.exchange()));
            }, () -> LOG.debug("NAVIGATION_TICK_OUT result=no_exchange viewSize={}", navigation.view().size()));
        } catch (RuntimeException ex) {
            LOG.error("NAVIGATION_CYCLE reason=runtime_error", ex);
        }
    }

    @Override
    public void messageReceived(NodeId peer, ProtocolMessage message) {
        NodeLog.network(LOG, "NAVIGATION_IN peer={} type={} requestId={} candidates={}",
                NodeLog.node(peer.value()), message.type(), message.requestId(),
                message.navigation().candidates().size());
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                switch (message.type()) {
                    case NAVIGATION_REQUEST -> {
                        var response = navigation.request(peer.value(), message.navigation(), now);
                        ProtocolMessage responseMessage = ProtocolMessage.navigationGossip(
                                MessageType.NAVIGATION_RESPONSE, message.requestId(), response);
                        NodeLog.network(LOG, "NAVIGATION_SEND peer={} type={} requestId={} candidates={}",
                                NodeLog.node(peer.value()), responseMessage.type(), responseMessage.requestId(),
                                response.candidates().size());
                        sender.replyMessage(peer, responseMessage);
                    }
                    case NAVIGATION_RESPONSE ->
                        navigation.response(peer.value(), message.navigation(), now);
                    default -> throw new IllegalArgumentException(
                            "Unsupported navigation message: " + message.type());
                }
                LOG.info("NAVIGATION_OUT peer={} type={} requestId={} viewSize={} result=accepted",
                        NodeLog.node(peer.value()), message.type(), message.requestId(), navigation.view().size());
            } catch (RuntimeException ex) {
                LOG.warn("NAVIGATION_EXCHANGE_REJECTED peer={} reason={}", NodeLog.node(peer.value()), ex.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public void subscribe(String topicId) {
        LOG.info("NAVIGATION_SUBSCRIBE topic={}", NodeLog.topic(topicId));
        // Persist the topic id to the subscription store.
        subscriptions.add(topicId);
        // Update the protocol state
        navigation.subscribe(topicId);
        LOG.info("NAVIGATION_SUBSCRIPTIONS count={}", navigation.subscriptions().size());
    }

    public void unsubscribe(String topicId) {
        LOG.info("NAVIGATION_UNSUBSCRIBE topic={}", NodeLog.topic(topicId));
        // Remove the topic id from the subscription store.
        subscriptions.remove(topicId);
        // Update the protocol state
        navigation.unsubscribe(topicId);
        LOG.info("NAVIGATION_SUBSCRIPTIONS count={}", navigation.subscriptions().size());
    }
}
