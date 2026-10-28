package org.pubsub.prototype.node.service;

import org.pubsub.prototype.protocol.MessageType;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.securecyclon.GossipExchange;
import org.pubsub.prototype.securecyclon.SecureCyclon;
import org.pubsub.prototype.transport.TransportSender;
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
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs SecureCyclon to maintain a sample of reachable peers. A scheduled cycle
 * sends gossip through {@link TransportSender}; inbound exchanges and peer
 * availability updates run on one executor. Newly accepted violation proofs
 * are forwarded to a bounded random subset of peers.
 */
public final class PeerSamplingProtocolService implements CyclicTransportService {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(PeerSamplingProtocolService.class);

    // Runs sampling cycles, incoming messages, and connection updates on one thread.
    private final ScheduledExecutorService executor = AsyncUtil.scheduledSingleThread("peer-sampling");

    private final SecureCyclon sampling;
    private final long interval;
    private final int proofFanout;
    private final Random proofFanoutRandom;
    private final NodeEndpoint self;
    private TransportSender sender;

    public PeerSamplingProtocolService(NodeEndpoint self, NodeConfig.SamplingSection config) {
        this.self = self;
        this.interval = config.cycleIntervalMs;
        this.proofFanout = config.proofFanout;
        this.proofFanoutRandom = new Random(config.randomSeed);
        Random samplingRandom = new Random(config.randomSeed);
        sampling = new SecureCyclon(self, config.viewSize, config.swapLength, interval,
                config.ageThreshold, samplingRandom, LOG::info);
        NodeLog.init(LOG,
                "SAMPLING_CONFIGURED advertised={}:{} viewSize={} swapLength={} cycleMs={} ageThreshold={} proofFanout={}",
                self.host(), self.port(), config.viewSize, config.swapLength, interval,
                config.ageThreshold, proofFanout);
    }

    public SecureCyclon sampling() {
        return sampling;
    }

    @Override
    public void attach(TransportSender sender) {
        this.sender = sender;
    }

    @Override
    public void start() {
        NodeLog.init(LOG, "SAMPLING_STARTED cycleMs={}", interval);
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
        if (outgoing == null) {
            LOG.info("SAMPLING_CYCLE_OUT nodeId={} result=no_exchange viewSize={}", self.nodeId(),
                    sampling.view().size());
            return;
        }

        try {
            ProtocolMessage request = ProtocolMessage.gossip(
                    MessageType.SECURECYCLON_REQUEST, outgoing.requestId(), outgoing.exchange());
            NodeLog.network(LOG, "SAMPLING_SEND peer={} type={} requestId={} descriptors={} samples={} proofs={}",
                    NodeLog.node(outgoing.partner().nodeId()), request.type(),
                    request.requestId(), request.exchange().descriptors().size(),
                    request.exchange().samples().size(), request.exchange().proofs().size());
            sender.sendMessage(outgoing.partner(), request);
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
        NodeLog.network(LOG, "SAMPLING_IN peer={} type={} requestId={} descriptors={} samples={} proofs={}",
                NodeLog.node(peer.value()), message.type(), message.requestId(),
                message.exchange().descriptors().size(), message.exchange().samples().size(),
                message.exchange().proofs().size());
        executor.execute(() -> {
            try {
                switch (message.type()) {
                    case SECURECYCLON_REQUEST -> {
                        var response = sampling.handleSwapRequest(
                                peer.value(), message.exchange(), System.currentTimeMillis());
                        ProtocolMessage responseMessage = ProtocolMessage.gossip(
                                MessageType.SECURECYCLON_RESPONSE, message.requestId(), response);
                        NodeLog.network(LOG,
                                "SAMPLING_SEND peer={} type={} requestId={} descriptors={} samples={} proofs={}",
                                NodeLog.node(peer.value()), responseMessage.type(), responseMessage.requestId(),
                                response.descriptors().size(), response.samples().size(), response.proofs().size());
                        sender.replyMessage(peer, responseMessage);
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
                LOG.info("SAMPLING_OUT peer={} type={} requestId={} viewSize={} result=accepted",
                        NodeLog.node(peer.value()), message.type(), message.requestId(), sampling.view().size());
            } catch (IllegalArgumentException ex) {
                LOG.warn("{} peer={} reason={}",
                        SecureCyclonEvent.EXCHANGE_REJECTED, NodeLog.node(peer.value()), ex.getMessage());
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
        LOG.info("SAMPLING_SEED_RESOLVED peer={} endpoint={}:{}", NodeLog.node(peer.nodeId()), peer.host(),
                peer.port());
        sampling.bootstrap(peer);
        LOG.info("SAMPLING_VIEW_UPDATED size={}", sampling.view().size());
    }

    /**
     * Records transport liveness on the sampling executor, preserving protocol
     * serialization.
     */
    public void peerDisconnected(NodeId peer) {
        LOG.info("SAMPLING_PEER_UNAVAILABLE peer={}", NodeLog.node(peer.value()));
        submit(() -> sampling.peerUnavailable(peer.value(), System.currentTimeMillis()));
    }

    /**
     * A verified HELLO connection is stronger evidence than an old gossip
     * descriptor.
     */
    public void peerConnected(NodeId peer) {
        LOG.info("SAMPLING_PEER_AVAILABLE peer={}", NodeLog.node(peer.value()));
        submit(() -> sampling.peerAvailable(peer.value()));
    }

    private void submit(Runnable task) {
        if (executor.isShutdown())
            return;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shutdown may race a final Netty connection callback.
        }
    }

    private void disseminateNewProofs(NodeId sourcePeer) {
        for (var proof : sampling.drainProofs()) {
            // Construct a message that contains the new proof
            GossipExchange gossip = new GossipExchange(List.of(), List.of(), List.of(proof));

            // Disseminate the new proof to proofFanout random peers in the view
            List<NodeEndpoint> recipients = new ArrayList<>(sampling.view());
            // Do not send the new proof back to the sender
            recipients.removeIf(candidate -> candidate.nodeId().equals(sourcePeer.value()));
            Collections.shuffle(recipients, proofFanoutRandom);
            int recipientCount = Math.min(proofFanout, recipients.size());

            for (NodeEndpoint recipient : recipients.subList(0, recipientCount)) {
                NodeId target = new NodeId(recipient.nodeId());
                ProtocolMessage proofMessage = ProtocolMessage.gossip(
                        MessageType.SECURECYCLON_PROOF, UUID.randomUUID(), gossip);
                NodeLog.network(LOG, "SAMPLING_SEND peer={} type={} requestId={} proofs=1",
                        NodeLog.node(target.value()), proofMessage.type(), proofMessage.requestId());
                sender.replyMessage(target, proofMessage);
            }
        }
    }
}
