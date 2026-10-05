package org.pubsub.prototype.node.runtimes;

import org.pubsub.prototype.navigation.NavigationLayer;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.navigation.TopicIndex;
import org.pubsub.prototype.node.util.AsyncUtil;
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
 * Bridges the transport-free Vicinity navigation engine to outbound transport operations and the topic registry.
 */
public final class NavigationRuntime implements CyclicTransportRuntime {

    public record Settings(int capacity, int routingBase, long cycleIntervalMs, long staleAfterMs) {
    }

    //Default logger
    private static final Logger LOG = LoggerFactory.getLogger(NavigationRuntime.class);

    private final ScheduledExecutorService executor =
            AsyncUtil.scheduledSingleThread("navigation");
    private final NavigationLayer navigation;
    private final SubscriptionStore subscriptions;
    private final Supplier<List<String>> activeTopicsSupplier;
    private final Supplier<List<NodeEndpoint>> samplingViewSupplier;
    private final long interval;
    private final String nodeId;
    private TransportSender sender;
    private List<String> lastActiveTopics = List.of();

    /**
     * @param activeTopicsSupplier method that supplies a fresh snapshot on every navigation cycle so
     *                             registry updates affect the topic index; a fixed list
     *                             would preserve only the startup snapshot.
     * @param samplingViewSupplier method that supplies the current peer-sampling view for each cycle.
     */
    public NavigationRuntime(String nodeId, String host, int port, SubscriptionStore subscriptions,
                             Settings settings, Supplier<List<String>> activeTopicsSupplier,
                             Supplier<List<NodeEndpoint>> samplingViewSupplier) {
        this.nodeId = nodeId;
        this.subscriptions = subscriptions;
        this.activeTopicsSupplier = activeTopicsSupplier;
        this.samplingViewSupplier = samplingViewSupplier;
        this.interval = settings.cycleIntervalMs();
        this.navigation = new NavigationLayer(nodeId, host, port, subscriptions.snapshot(),
                settings.capacity(), settings.routingBase(), settings.staleAfterMs(), new Random(), LOG::info);
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
        LOG.info("NAVIGATION_STARTED nodeId={} subscriptions={}", nodeId, subscriptions.snapshot());
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runCycle() {
        try {
            List<String> active = activeTopicsSupplier.get();
            if (!active.equals(lastActiveTopics)) {
                lastActiveTopics = active;
                navigation.updateTopicIndex(TopicIndex.of(active));
            }
            long now = System.currentTimeMillis();
            for (NodeEndpoint sample : samplingViewSupplier.get()) {
                navigation.ingestSample(sample.nodeId(), sample.host(), sample.port(), now);
            }
            navigation.cycle(now).ifPresent(out -> sender.sendSampling(
                    new NodeEndpoint(out.peer().nodeId(), out.peer().host(), out.peer().port()),
                    ProtocolMessage.navigationGossip(MessageType.NAVIGATION_REQUEST, out.requestId(), out.exchange())));
        } catch (RuntimeException ex) {
            LOG.error("NAVIGATION_CYCLE reason=runtime_error", ex);
        }
    }

    @Override
    public void messageReceived(NodeId peer, ProtocolMessage message) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                switch (message.type()) {
                    case NAVIGATION_REQUEST -> {
                        var response = navigation.request(peer.value(), message.navigation(), now);
                        ProtocolMessage responseMessage = ProtocolMessage.navigationGossip(
                                MessageType.NAVIGATION_RESPONSE, message.requestId(), response);
                        sender.replySampling(peer, responseMessage);
                    }
                    case NAVIGATION_RESPONSE ->
                            navigation.response(peer.value(), message.navigation(), now);
                    default -> throw new IllegalArgumentException(
                            "Unsupported navigation message: " + message.type());
                }
            } catch (RuntimeException ex) {
                LOG.warn("NAVIGATION_EXCHANGE_REJECTED nodeId={} peerNodeId={} reason={}", nodeId, peer.value(), ex.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public void subscribe(String topicId) {
        // Persist the topic id to the subscription store.
        subscriptions.add(topicId);
        // Update the protocol state
        navigation.subscribe(topicId);
    }

    public void unsubscribe(String topicId) {
        // Remove the topic id from the subscription store.
        subscriptions.remove(topicId);
        // Update the protocol state
        navigation.unsubscribe(topicId);
    }
}
