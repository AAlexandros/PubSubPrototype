package org.pubsub.prototype.node.runtimes;

import org.pubsub.prototype.protocol.MessageType;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.securecyclon.GossipExchange;
import org.pubsub.prototype.securecyclon.SecureCyclon;
import org.pubsub.prototype.transport.PubSubTransport;
import org.pubsub.prototype.util.SecureCyclonEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the serialized, transport-free protocol to the existing Netty runtime.
 */
public final class PeerSamplingRuntime implements CyclicTransportRuntime {

    public record Settings(int viewSize, int swapLength, long cycleIntervalMs,
                           int ageThreshold, int proofFanout, long randomSeed) {
    }

    //Default logger
    private static final Logger LOG = LoggerFactory.getLogger(PeerSamplingRuntime.class);

    private final ScheduledExecutorService executor =
            AsyncUtil.scheduledSingleThread("peer-sampling");
    private final SecureCyclon sampling;
    private final long interval;
    private final int proofFanout;
    private final Random proofFanoutRandom;
    private final NodeEndpoint self;
    private PubSubTransport transport;

    public PeerSamplingRuntime(NodeEndpoint self, Settings settings) {
        this.self = self;
        this.interval = settings.cycleIntervalMs();
        this.proofFanout = settings.proofFanout();
        this.proofFanoutRandom = new Random(settings.randomSeed());
        Random samplingRandom = new Random(settings.randomSeed());
        sampling = new SecureCyclon(self, settings.viewSize(), settings.swapLength(), interval,
                settings.ageThreshold(), samplingRandom, LOG::info);
    }

    public SecureCyclon sampling() {
        return sampling;
    }

    @Override
    public void attach(PubSubTransport transport) {
        this.transport = transport;
    }

    @Override
    public void start() {
        LOG.info("PEER_SAMPLING_STARTED nodeId={} every interval={}ms", self.nodeId(), interval);
        executor.scheduleWithFixedDelay(this::runCycle, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runCycle() {
        SecureCyclon.Outgoing outgoing;
        try {
            outgoing = sampling.executeCycle(System.currentTimeMillis()).orElse(null);
        } catch (RuntimeException | AssertionError ex) {
            LOG.error(SecureCyclonEvent.CYCLE + " reason=fatal_logic_error", ex);
            throw ex;
        }
        if (outgoing == null) return;

        try {
            ProtocolMessage request = ProtocolMessage.gossip(
                    MessageType.SECURECYCLON_REQUEST, outgoing.requestId(), outgoing.exchange());
            transport.sendSampling(outgoing.partner(), request);
        } catch (RuntimeException ex) {
            LOG.warn(SecureCyclonEvent.CYCLE + " reason=transport_error; will_retry", ex);
        }
    }

    /**
     * Queues an authenticated SecureCyclon message for serialized processing.
     * Handles swap requests, responses, and proofs, rejecting invalid input and
     * immediately drains every violation proof newly accepted since the previous
     * message and sends it to a random subset of at most {@code proofFanout}
     * current neighbors, excluding the sender.
    */
    @Override
    public void messageReceived(NodeId peer, ProtocolMessage message) {
        executor.execute(() -> {
            try {
                switch (message.type()) {
                    case SECURECYCLON_REQUEST -> {
                        var response = sampling.handleSwapRequest(
                                peer.value(), message.exchange(), System.currentTimeMillis());
                        ProtocolMessage responseMessage = ProtocolMessage.gossip(
                                MessageType.SECURECYCLON_RESPONSE, message.requestId(), response);
                        transport.replySampling(peer, responseMessage);
                    }
                    case SECURECYCLON_RESPONSE -> sampling.handleSwapResponse(
                            peer.value(), message.requestId(), message.exchange(), System.currentTimeMillis());
                    case SECURECYCLON_PROOF -> {
                        var exchange = message.exchange();
                        if (!exchange.descriptors().isEmpty() || !exchange.samples().isEmpty()
                                || exchange.proofs().size() != 1) {
                            throw new IllegalArgumentException("invalid_proof_structure");
                        }
                        sampling.acceptProof(exchange.proofs().getFirst(), System.currentTimeMillis());
                    }
                    default -> throw new IllegalArgumentException(
                            "Unsupported peer-sampling message: " + message.type());
                }
            } catch (IllegalArgumentException ex) {
                LOG.warn("{} nodeId={} peerNodeId={} reason={}",
                        SecureCyclonEvent.EXCHANGE_REJECTED, self.nodeId(), peer.value(), ex.getMessage());
            } finally {
                disseminateNewProofs(peer);
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public void seedResolved(NodeEndpoint peer) {
        sampling.bootstrap(peer);
    }

    private void disseminateNewProofs(NodeId sender) {
        for (var proof : sampling.drainProofs()) {
            // Construct a message that contains the new proof
            GossipExchange gossip = new GossipExchange(List.of(), List.of(), List.of(proof));

            // Disseminate the new proof to proofFanout random peers in the view
            List<NodeEndpoint> recipients = new ArrayList<>(sampling.view());
            // Do not send the new proof back to the sender
            recipients.removeIf(candidate -> candidate.nodeId().equals(sender.value()));
            Collections.shuffle(recipients, proofFanoutRandom);
            int recipientCount = Math.min(proofFanout, recipients.size());

            for (NodeEndpoint recipient : recipients.subList(0, recipientCount)) {
                NodeId target = new NodeId(recipient.nodeId());
                ProtocolMessage proofMessage = ProtocolMessage.gossip(
                        MessageType.SECURECYCLON_PROOF, UUID.randomUUID(), gossip);
                transport.replySampling(target, proofMessage);
            }
        }
    }
}
