package org.pubsub.prototype.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.protocol.ProtocolException;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.util.SecureCyclonEvent;
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

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(PeerSessionHandler.class);
    private static final long HANDSHAKE_TIMEOUT_MS = 3_000;

    private final PubSubTransport transport;
    private final TransportConfig config;
    private final NodeIdentity identity;
    private final PeerEndpoint endpoint;
    private final NodeId expectedNodeId;
    // Does the connection start from this client?
    private final boolean outbound;
    private final Map<UUID, PendingPing> pendingPings = new ConcurrentHashMap<>();
    private ChannelHandlerContext context;
    private NodeId remoteNodeId;
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> handshakeTimeout;
    private boolean active;
    private volatile boolean reconnectSuppressed;

    PeerSessionHandler(PubSubTransport transport, TransportConfig config, NodeIdentity identity,
                       PeerEndpoint endpoint, NodeId expectedNodeId, boolean outbound) {
        this.transport = transport;
        this.config = config;
        this.identity = identity;
        this.endpoint = endpoint;
        this.expectedNodeId = expectedNodeId;
        this.outbound = outbound;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Store the context object in order to be able to use it for chanel interactions
        this.context = ctx;
        // If the handshake does not complete within HANDSHAKE_TIMEOUT_MS, terminate the connection
        handshakeTimeout = ctx.executor().schedule(() -> {
            // Close using the default Netty handler close mechanism
            if (!active) ctx.close();
        }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // If this is the initiator of a connection, send a HELLO message
        if (outbound) {
            ctx.writeAndFlush(ProtocolMessage.hello(identity.nodeId(), config.nodeName())
                    .withEndpoint(transport.advertisedEndpoint()));
        }
    }

    /**    (non-Javadoc)
     * Handles incoming protocol messages from the peer.
     * Transport messages, e.g., HELLO, HELLO_ACK, PING, are handled by this object via some private method.
     * Application messages are getting delegated to the respective higher-level protocol implementations.
     * @see io.netty.channel.SimpleChannelInboundHandler#channelRead0(io.netty.channel.ChannelHandlerContext, java.lang.Object)
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage message) {
        try {
            switch (message.type()) {
                // Transport messages
                case HELLO -> handleHello(ctx, message);
                case HELLO_ACK -> handleHelloAck(message);
                case PING -> handlePing(ctx, message);
                case PONG -> handlePong(message);
                case EVENT -> handleEvent(message);

                // Application messages
                case SECURECYCLON_REQUEST, SECURECYCLON_RESPONSE, SECURECYCLON_PROOF -> {
                    if (!active) throw new ProtocolException("SecureCyclon before handshake");
                    transport.recordSampling(remoteNodeId, message);
                }
                case NAVIGATION_REQUEST, NAVIGATION_RESPONSE -> {
                    if (!active) throw new ProtocolException("Navigation before handshake");
                    transport.recordSampling(remoteNodeId, message);
                }
                case DISSEMINATION_REQUEST, DISSEMINATION_RESPONSE -> {
                    if (!active) throw new ProtocolException("Dissemination before handshake");
                    transport.recordSampling(remoteNodeId, message);
                }
                default -> throw new ProtocolException(
                    "Unsupported message type: " + message.type()
                );
            }
        } catch (ProtocolException ex) {
            LOG.warn("PEER_DISCONNECTED reason=protocol_error message={}", ex.getMessage());
            ctx.close();
        }
    }

    private void handleHello(ChannelHandlerContext ctx, ProtocolMessage message) {
        if (outbound) throw new ProtocolException("Unexpected HELLO on outbound connection");
        if (active) throw new ProtocolException("Repeated handshake");
        NodeId nodeId = new NodeId(message.nodeId());
        validateRemoteIdentity(nodeId);
        transport.resolved(endpoint, nodeId, message.endpoint());
        remoteNodeId = nodeId;
        ctx.writeAndFlush(ProtocolMessage.helloAck(identity.nodeId())
                .withEndpoint(transport.advertisedEndpoint()));
        activate();
    }

    private void handleHelloAck(ProtocolMessage message) {
        if (!outbound) throw new ProtocolException("Unexpected HELLO_ACK on inbound connection");
        if (active) throw new ProtocolException("Repeated handshake");
        NodeId nodeId = new NodeId(message.nodeId());
        validateRemoteIdentity(nodeId);
        transport.resolved(endpoint, nodeId, message.endpoint());
        remoteNodeId = nodeId;
        activate();
    }

    private void validateRemoteIdentity(NodeId nodeId) {
        if (nodeId.equals(identity.nodeId())) {
            throw new ProtocolException("Self-connections are not allowed");
        }
        if (expectedNodeId != null && !expectedNodeId.equals(nodeId)) {
            throw new ProtocolException("Learned endpoint identity mismatch");
        }
    }

    private void activate() {
        if (active) {
            return;
        }
        active = true;
        transport.resetBackoff(endpoint);
        if (handshakeTimeout != null) handshakeTimeout.cancel(false);
        if (!transport.registerActive(remoteNodeId, this)) return;
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
                context.close();
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

    void sendMessage(ProtocolMessage message) {
        if (active && context != null && context.channel().isActive()) context.writeAndFlush(message);
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
        LOG.warn("{} nodeId={} peerNodeId={} reason=malformed_frame detail={}",
                SecureCyclonEvent.EXCHANGE_REJECTED, identity.nodeId().value(), peerLabel(), cause.toString());
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
        if (handshakeTimeout != null) handshakeTimeout.cancel(false);
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
