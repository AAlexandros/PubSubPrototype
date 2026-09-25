package org.pubsub.prototype.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.protocol.ProtocolException;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pubsub.prototype.util.TransportConstants.CONFIG_FIELD;
import static org.pubsub.prototype.util.TransportConstants.CONNECT_TIMEOUT_MS;
import static org.pubsub.prototype.util.TransportConstants.IDENTITY_FIELD;
import static org.pubsub.prototype.util.TransportConstants.MAX_FRAME_LENGTH;
import static org.pubsub.prototype.util.Validators.require;

public final class PubSubTransport implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PubSubTransport.class);
    private final TransportConfig config;
    private final NodeIdentity identity;
    private final NodeEndpoint advertisedEndpoint;
    private final TransportListener listener;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Map<String, ReconnectBackoff> backoffs = new ConcurrentHashMap<>();
    private final Map<NodeId, PeerSessionHandler> activePeers = new ConcurrentHashMap<>();
    private final Map<NodeId, ConcurrentLinkedQueue<ProtocolMessage>> queued = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private Channel serverChannel;

    /**
     * @param advertisedEndpoint the address shared with peers, or {@code null} when this transport is not discoverable
     */
    public PubSubTransport(TransportConfig config, NodeIdentity identity, NodeEndpoint advertisedEndpoint,
                           TransportListener listener) {
        this.config = Objects.requireNonNull(config, CONFIG_FIELD);
        this.identity = Objects.requireNonNull(identity, IDENTITY_FIELD);
        require(advertisedEndpoint == null || advertisedEndpoint.nodeId().equals(identity.nodeId().value()),
                "Advertised endpoint identity mismatch");
        this.advertisedEndpoint = advertisedEndpoint;
        this.listener = listener == null ? new TransportListener() {
        } : listener;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }

    NodeEndpoint advertisedEndpoint() {
        return advertisedEndpoint;
    }

    public void sendSampling(NodeEndpoint peer, ProtocolMessage message) {
        send(peer, message);
    }

    /** Sends an event only to the selected overlay peer, connecting dynamically when necessary. */
    public void sendEvent(NodeEndpoint peer, EventEnvelope event) {
        send(peer, ProtocolMessage.event(event));
    }

    private void send(NodeEndpoint peer, ProtocolMessage message) {
        NodeId id = new NodeId(peer.nodeId());

        // Get the handler object that manages the active connection with the specific node
        PeerSessionHandler handler = activePeers.get(id);
        if (handler != null) {
            handler.sendMessage(message);
            return;
        }

        // If there is no handler for the specific node, try to establish a new connection with that node
        PeerEndpoint endpoint = new PeerEndpoint(peer.host(), peer.port());
        ConcurrentLinkedQueue<ProtocolMessage> messages =
                queued.computeIfAbsent(id, ignored -> new ConcurrentLinkedQueue<>());
        messages.add(message);

        // Registration may have finished between the initial lookup and queue insertion.
        handler = activePeers.get(id);
        if (handler != null) {
            if (messages.remove(message)) {
                handler.sendMessage(message);
            }
            return;
        }
        connect(endpoint, id);
    }

    public void replySampling(NodeId peer, ProtocolMessage message) {
        PeerSessionHandler handler = activePeers.get(peer);
        if (handler != null) handler.sendMessage(message);
    }

    void recordSampling(NodeId peer, ProtocolMessage message) {
        listener.samplingReceived(peer, message);
    }

    void resolved(PeerEndpoint endpoint, NodeId id, NodeEndpoint advertisedEndpoint) {
        if (advertisedEndpoint != null && !advertisedEndpoint.nodeId().equals(id.value())) {
            throw new ProtocolException("Advertised identity mismatch");
        }
        if (endpoint != null && advertisedEndpoint != null && config.peers().contains(endpoint)) {
            listener.seedResolved(advertisedEndpoint);
        }
    }

    private void flushQueued(NodeId id, PeerSessionHandler handler) {
        var messages = queued.remove(id);
        if (messages != null) {
            ProtocolMessage message;
            while ((message = messages.poll()) != null) handler.sendMessage(message);
        }
    }

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerBootstrap serverBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer(null, false, null))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            serverChannel = serverBootstrap.bind(config.listenHost(), config.listenPort()).sync().channel();
        } catch (InterruptedException | RuntimeException exception) {
            running.set(false);
            throw exception;
        }
        LOG.info("NODE_STARTED name={} nodeId={} listen={}:{}", config.nodeName(),
                identity.nodeId().value(), config.listenHost(), config.listenPort());

        for (PeerEndpoint peer : config.peers()) {
            backoffs.put(peer.key(), new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs()));
            connect(peer, null);
        }
    }

    public int activePeerCount() {
        return activePeers.size();
    }

    public void broadcastEvent(EventEnvelope event) {
        for (PeerSessionHandler handler : activePeers.values()) {
            handler.sendEvent(event);
        }
    }

    public void forwardEvent(EventEnvelope event, NodeId exceptPeer) {
        for (Map.Entry<NodeId, PeerSessionHandler> entry : activePeers.entrySet()) {
            if (!entry.getKey().equals(exceptPeer)) {
                entry.getValue().sendEvent(event);
            }
        }
    }

    void recordEvent(NodeId nodeId, EventEnvelope event) {
        listener.eventReceived(nodeId, event);
    }

    boolean registerActive(NodeId nodeId, PeerSessionHandler handler) {
        while (true) {
            PeerSessionHandler previous = activePeers.putIfAbsent(nodeId, handler);
            if (previous == null || previous == handler) {
                flushQueued(nodeId, handler);
                listener.peerConnected(nodeId);
                return true;
            }
            if (handler.isPreferredOver(previous)) {
                if (activePeers.replace(nodeId, previous, handler)) {
                    previous.closeWithoutReconnect();
                    flushQueued(nodeId, handler);
                    listener.peerConnected(nodeId);
                    return true;
                }
            } else {
                handler.closeWithoutReconnect();
                flushQueued(nodeId, previous);
                return false;
            }
        }
    }

    void unregisterActive(NodeId nodeId, PeerSessionHandler handler) {
        if (nodeId != null && activePeers.remove(nodeId, handler)) {
            listener.peerDisconnected(nodeId);
        }
    }

    void recordPong(NodeId nodeId, long rttMs) {
        listener.pongReceived(nodeId, rttMs);
    }

    void scheduleReconnect(PeerEndpoint endpoint) {
        if (!running.get() || endpoint == null || !config.peers().contains(endpoint)) {
            return;
        }
        ReconnectBackoff backoff = backoffs.computeIfAbsent(endpoint.key(),
                ignored -> new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs()));
        OptionalLong nextDelay = backoff.nextDelayMs();
        if (nextDelay.isEmpty()) {
            LOG.warn("RECONNECT_EXHAUSTED peer={} attempts={}",
                    endpoint.key(), ReconnectBackoff.MAX_RETRIES + 1);
            return;
        }
        long delayMs = nextDelay.getAsLong();
        LOG.info("RECONNECT_SCHEDULED peer={} delayMs={}", endpoint.key(), delayMs);
        workerGroup.schedule(() -> connect(endpoint, null), delayMs, TimeUnit.MILLISECONDS);
    }

    void resetBackoff(PeerEndpoint endpoint) {
        if (endpoint != null) {
            backoffs.computeIfAbsent(endpoint.key(),
                    ignored -> new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs())).reset();
        }
    }

    private void connect(PeerEndpoint endpoint, NodeId expectedNodeId) {
        if (!running.get() || !connecting.add(endpoint.key())) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(initializer(endpoint, true, expectedNodeId));
        bootstrap.connect(endpoint.host(), endpoint.port()).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                future.channel().closeFuture().addListener(ignored -> {
                    connecting.remove(endpoint.key());
                    if (expectedNodeId != null) queued.remove(expectedNodeId);
                });
            } else {
                connecting.remove(endpoint.key());
                if (expectedNodeId != null) queued.remove(expectedNodeId);
                scheduleReconnect(endpoint);
            }
        });
    }

    private ChannelInitializer<SocketChannel> initializer(PeerEndpoint endpoint, boolean outbound,
                                                          NodeId expectedNodeId) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
                ch.pipeline().addLast(new LengthFieldPrepender(4));
                ch.pipeline().addLast(new ProtocolFrameCodec());
                ch.pipeline().addLast(new PeerSessionHandler(
                        PubSubTransport.this, config, identity, endpoint, expectedNodeId, outbound));
            }
        };
    }

    @Override
    public void close() {
        running.set(false);
        for (PeerSessionHandler handler : activePeers.values()) {
            handler.closeWithoutReconnect();
        }
        activePeers.clear();
        queued.clear();
        connecting.clear();
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
