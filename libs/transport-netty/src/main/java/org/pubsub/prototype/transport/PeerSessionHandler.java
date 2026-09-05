package org.pubsub.prototype.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.protocol.ProtocolException;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class PeerSessionHandler extends SimpleChannelInboundHandler<ProtocolMessage> {
    private static final Logger LOG = LoggerFactory.getLogger(PeerSessionHandler.class);

    private final PubSubTransport transport;
    private final TransportConfig config;
    private final NodeIdentity identity;
    private final PeerEndpoint endpoint;
    private final boolean outbound;
    private final Map<UUID, PendingPing> pendingPings = new ConcurrentHashMap<>();
    private ChannelHandlerContext context;
    private NodeId remoteNodeId;
    private ScheduledFuture<?> pingTask;
    private boolean active;
    private volatile boolean reconnectSuppressed;

    PeerSessionHandler(PubSubTransport transport, TransportConfig config, NodeIdentity identity, PeerEndpoint endpoint, boolean outbound) {
        this.transport = transport;
        this.config = config;
        this.identity = identity;
        this.endpoint = endpoint;
        this.outbound = outbound;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
        if (outbound) {
            ctx.writeAndFlush(ProtocolMessage.hello(identity.nodeId(), config.nodeName()));
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage message) {
        try {
            switch (message.type()) {
                case HELLO -> handleHello(ctx, message);
                case HELLO_ACK -> handleHelloAck(message);
                case PING -> handlePing(ctx, message);
                case PONG -> handlePong(message);
                case EVENT -> handleEvent(message);
            }
        } catch (ProtocolException ex) {
            LOG.warn("PEER_DISCONNECTED reason=protocol_error message={}", ex.getMessage());
            ctx.close();
        }
    }

    private void handleHello(ChannelHandlerContext ctx, ProtocolMessage message) {
        NodeId nodeId = new NodeId(message.nodeId());
        if (nodeId.equals(identity.nodeId())) {
            throw new ProtocolException("Self-connections are not allowed");
        }
        remoteNodeId = nodeId;
        ctx.writeAndFlush(ProtocolMessage.helloAck(identity.nodeId()));
        activate();
    }

    private void handleHelloAck(ProtocolMessage message) {
        NodeId nodeId = new NodeId(message.nodeId());
        if (nodeId.equals(identity.nodeId())) {
            throw new ProtocolException("Self-connections are not allowed");
        }
        remoteNodeId = nodeId;
        activate();
    }

    private void activate() {
        if (active) {
            return;
        }
        active = true;
        transport.resetBackoff(endpoint);
        transport.registerActive(remoteNodeId, this);
        LOG.info("PEER_CONNECTED peer={}", remoteNodeId.value());
        pingTask = context.executor().scheduleAtFixedRate(this::sendPing, config.pingIntervalMs(), config.pingIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void sendPing() {
        if (!active || context == null || !context.channel().isActive()) {
            return;
        }
        UUID requestId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        ScheduledFuture<?> timeout = context.executor().schedule(() -> {
            PendingPing removed = pendingPings.remove(requestId);
            if (removed != null) {
                LOG.warn("PING_TIMEOUT peer={} requestId={}", peerLabel(), requestId);
            }
        }, config.pingTimeoutMs(), TimeUnit.MILLISECONDS);
        pendingPings.put(requestId, new PendingPing(sentAt, timeout));
        context.writeAndFlush(ProtocolMessage.ping(requestId, sentAt));
        LOG.info("PING_SENT peer={} requestId={}", peerLabel(), requestId);
    }

    private void handlePing(ChannelHandlerContext ctx, ProtocolMessage message) {
        if (!active) {
            throw new ProtocolException("PING before handshake");
        }
        ctx.writeAndFlush(ProtocolMessage.pong(message.requestId(), message.sentAt()));
    }

    private void handlePong(ProtocolMessage message) {
        if (!active) {
            throw new ProtocolException("PONG before handshake");
        }
        PendingPing pending = pendingPings.remove(message.requestId());
        if (pending == null) {
            return;
        }
        pending.timeout.cancel(false);
        long rttMs = Duration.between(pending.sentAt, Instant.now()).toMillis();
        LOG.info("PONG_RECEIVED peer={} requestId={} rttMs={}", peerLabel(), message.requestId(), rttMs);
        transport.recordPong(remoteNodeId, rttMs);
    }

    private void handleEvent(ProtocolMessage message) {
        if (!active) {
            throw new ProtocolException("EVENT before handshake");
        }
        transport.recordEvent(remoteNodeId, message.event());
    }

    void sendEvent(EventEnvelope event) {
        if (!active || context == null || !context.channel().isActive()) {
            return;
        }
        context.writeAndFlush(ProtocolMessage.event(event));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cleanup();
        if (outbound && !reconnectSuppressed) {
            transport.scheduleReconnect(endpoint);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("PEER_DISCONNECTED peer={} reason={}", peerLabel(), cause.toString());
        ctx.close();
    }

    void close() {
        if (context != null) {
            context.close();
        }
        cleanup();
    }

    void closeWithoutReconnect() {
        reconnectSuppressed = true;
        close();
    }

    boolean isPreferredOver(PeerSessionHandler other) {
        if (remoteNodeId == null) {
            return false;
        }
        boolean thisPreferred = preferredForPair();
        boolean otherPreferred = other != null && other.preferredForPair();
        if (thisPreferred != otherPreferred) {
            return thisPreferred;
        }
        return false;
    }

    private void cleanup() {
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        pendingPings.values().forEach(pending -> pending.timeout.cancel(false));
        pendingPings.clear();
        if (active) {
            LOG.info("PEER_DISCONNECTED peer={}", peerLabel());
        }
        active = false;
        transport.unregisterActive(remoteNodeId, this);
    }

    private String peerLabel() {
        return remoteNodeId == null ? (endpoint == null ? "unknown" : endpoint.key()) : remoteNodeId.value();
    }

    private boolean preferredForPair() {
        if (remoteNodeId == null) {
            return false;
        }
        int comparison = identity.nodeId().value().compareTo(remoteNodeId.value());
        return outbound == (comparison < 0);
    }

    private record PendingPing(Instant sentAt, ScheduledFuture<?> timeout) {
    }
}
