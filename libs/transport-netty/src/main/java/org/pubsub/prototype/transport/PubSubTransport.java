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
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PubSubTransport implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PubSubTransport.class);
    private static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private final TransportConfig config;
    private final NodeIdentity identity;
    private final TransportListener listener;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Map<String, ReconnectBackoff> backoffs = new ConcurrentHashMap<>();
    private final Map<NodeId, PeerSessionHandler> activePeers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Channel serverChannel;

    public PubSubTransport(TransportConfig config, NodeIdentity identity, TransportListener listener) {
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.listener = listener == null ? new TransportListener() {
        } : listener;
    }

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerBootstrap serverBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer(null, false))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        serverChannel = serverBootstrap.bind(config.listenHost(), config.listenPort()).sync().channel();
        LOG.info("NODE_STARTED name={} nodeId={} listen={}:{}", config.nodeName(), identity.nodeId().value(), config.listenHost(), config.listenPort());

        for (PeerEndpoint peer : config.peers()) {
            backoffs.put(peer.key(), new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs()));
            connect(peer);
        }
    }

    public int activePeerCount() {
        return activePeers.size();
    }

    void registerActive(NodeId nodeId, PeerSessionHandler handler) {
        while (true) {
            PeerSessionHandler previous = activePeers.putIfAbsent(nodeId, handler);
            if (previous == null || previous == handler) {
                listener.peerConnected(nodeId);
                return;
            }
            if (handler.isPreferredOver(previous)) {
                if (activePeers.replace(nodeId, previous, handler)) {
                    previous.closeWithoutReconnect();
                    listener.peerConnected(nodeId);
                    return;
                }
            } else {
                handler.closeWithoutReconnect();
                return;
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
        if (!running.get() || endpoint == null) {
            return;
        }
        ReconnectBackoff backoff = backoffs.computeIfAbsent(endpoint.key(),
                ignored -> new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs()));
        long delayMs = backoff.nextDelayMs();
        LOG.info("RECONNECT_SCHEDULED peer={} delayMs={}", endpoint.key(), delayMs);
        workerGroup.schedule(() -> connect(endpoint), delayMs, TimeUnit.MILLISECONDS);
    }

    void resetBackoff(PeerEndpoint endpoint) {
        if (endpoint != null) {
            backoffs.computeIfAbsent(endpoint.key(),
                    ignored -> new ReconnectBackoff(config.reconnectInitialMs(), config.reconnectMaxMs())).reset();
        }
    }

    private void connect(PeerEndpoint endpoint) {
        if (!running.get()) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(initializer(endpoint, true));
        bootstrap.connect(endpoint.host(), endpoint.port()).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                scheduleReconnect(endpoint);
            }
        });
    }

    private ChannelInitializer<SocketChannel> initializer(PeerEndpoint endpoint, boolean outbound) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
                ch.pipeline().addLast(new LengthFieldPrepender(4));
                ch.pipeline().addLast(new ProtocolFrameCodec());
                ch.pipeline().addLast(new PeerSessionHandler(PubSubTransport.this, config, identity, endpoint, outbound));
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
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
