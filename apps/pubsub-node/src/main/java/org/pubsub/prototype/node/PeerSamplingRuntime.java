package org.pubsub.prototype.node;

import org.pubsub.prototype.protocol.*;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.securecyclon.SecureCyclon;
import org.pubsub.prototype.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Random;
import java.util.concurrent.*;

/** Bridges the serialized, transport-free protocol to the existing Netty runtime. */
final class PeerSamplingRuntime implements TransportListener, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PeerSamplingRuntime.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final SecureCyclon sampling;
    private final TransportListener events;
    private final long interval;
    private final NodeEndpoint self;
    private PubSubTransport transport;

    PeerSamplingRuntime(NodeEndpoint self, NodeConfig.SamplingSection config, TransportListener events) {
        this.self = self; this.events = events; this.interval = config.cycleIntervalMs;
        sampling = new SecureCyclon(self, config.viewSize, config.swapLength, interval,
                config.ageThreshold, new Random(config.randomSeed), LOG::info);
    }
    SecureCyclon sampling() { return sampling; }
    void attach(PubSubTransport transport) { this.transport = transport; transport.advertise(self); }
    void start() {
        LOG.info("PEER_SAMPLING_STARTED nodeId={} viewSize={} cycle=0", self.nodeId(), sampling.view().size());
        executor.scheduleWithFixedDelay(() -> {
            try {
                sampling.cycle(System.currentTimeMillis()).ifPresent(out -> transport.sendSampling(out.partner(),
                        ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, out.requestId(), out.exchange())));
            } catch (RuntimeException ex) { LOG.error("SECURECYCLON_CYCLE reason=runtime_error", ex); }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }
    @Override public void seedResolved(NodeEndpoint peer) { sampling.bootstrap(peer); }
    @Override public void samplingReceived(NodeId peer, ProtocolMessage message) {
        executor.execute(() -> {
            try {
                if (message.type() == MessageType.SECURECYCLON_REQUEST) {
                    var response = sampling.request(peer.value(), message.exchange(), System.currentTimeMillis());
                    transport.replySampling(peer, ProtocolMessage.gossip(MessageType.SECURECYCLON_RESPONSE, message.requestId(), response));
                } else if (message.type() == MessageType.SECURECYCLON_PROOF) {
                    var exchange = message.exchange();
                    if (!exchange.descriptors().isEmpty() || !exchange.samples().isEmpty() || exchange.proofs().size() != 1) {
                        LOG.warn("SECURECYCLON_EXCHANGE_REJECTED nodeId={} peerNodeId={} reason=invalid_proof_structure", self.nodeId(), peer.value());
                        return;
                    }
                    sampling.acceptProof(exchange.proofs().getFirst(), System.currentTimeMillis());
                } else sampling.response(peer.value(), message.requestId(), message.exchange(), System.currentTimeMillis());
            } catch (IllegalArgumentException ex) {
                // Invalid descriptors are never adopted. Proven offenders may be removed by the defender.
            } finally {
                for (var proof : sampling.drainProofs()) {
                    var gossip = new org.pubsub.prototype.securecyclon.GossipExchange(java.util.List.of(), java.util.List.of(), java.util.List.of(proof));
                    sampling.view().stream().filter(p -> !p.nodeId().equals(peer.value())).limit(20).forEach(p ->
                            transport.replySampling(new NodeId(p.nodeId()), ProtocolMessage.gossip(MessageType.SECURECYCLON_PROOF, java.util.UUID.randomUUID(), gossip)));
                }
            }
        });
    }
    @Override public void eventReceived(NodeId peer, org.pubsub.prototype.event.EventEnvelope event) { events.eventReceived(peer, event); }
    @Override public void close() { executor.shutdownNow(); }
}
