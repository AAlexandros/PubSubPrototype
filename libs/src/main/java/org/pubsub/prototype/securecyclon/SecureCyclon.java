package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.sampling.PeerSamplingService;
import org.pubsub.prototype.util.SecureCyclonEvent;

import java.util.*;
import java.util.function.Consumer;

import static org.pubsub.prototype.util.SecureCyclonConstants.MAX_PROOFS_PER_EXCHANGE;

/**
 * PeerNet's non-tit-for-tat state machine, serialized independently of any transport.
 */
public final class SecureCyclon implements PeerSamplingService {
    public record Outgoing(NodeEndpoint partner, UUID requestId, GossipExchange exchange) {
    }

    private record Pending(String partnerId, UUID id, List<NodeDescriptor> sent) {
    }

    private record ValidatedProof(ViolationProof proof, String culprit) {
    }

    private record DetectedInconsistencies(Set<String> forkedDescriptorIds,
                                           List<ValidatedProof> proofs,
                                           boolean rejectExchange) {
    }

    private final NodeEndpoint self;
    private final int capacity, swapLength;
    private final long interval, historyAge;
    private final Random random;
    private final Consumer<String> log;
    private final List<NodeDescriptor> view = new ArrayList<>();
    private final Map<String, NodeDescriptor> history = new HashMap<>();
    private final Set<String> bootstrapped = new HashSet<>();
    private final Map<String, ViolationProof> blacklist = new LinkedHashMap<>();
    private final List<ViolationProof> newProofs = new ArrayList<>();
    private Pending pending;
    private long lastFresh = -1, cycle;

    public SecureCyclon(NodeEndpoint self, int capacity, int swapLength, long interval,
                        int ageThreshold, Random random, Consumer<String> log) {
        this.self = Objects.requireNonNull(self);
        this.capacity = capacity;
        this.swapLength = swapLength;
        this.interval = interval;
        this.historyAge = Math.multiplyExact(interval, (long) capacity + ageThreshold);
        this.random = Objects.requireNonNull(random);
        this.log = Objects.requireNonNull(log);
    }

    /**
     * Attempts to add one configured bootstrap peer to the local sampling view.
     * <p>
     * The node runtime calls this method after a successful HELLO handshake has
     * resolved a configured peer address to a verified {@link NodeEndpoint}. Each
     * invocation handles one peer, so runtimes configured with multiple bootstrap
     * peers call this method separately for every peer that connects successfully.
     * Peers discovered later through SecureCyclon gossip do not use this method.
     * </p>
     * <p>
     * The endpoint is added as a fresh descriptor only when it is not this node,
     * is not blacklisted, has not previously been bootstrapped, is not already
     * represented in the view, and the view has available capacity. Its node ID is
     * remembered in {@code bootstrapped}, preventing reconnects from repeatedly
     * inserting or pinning the configured seed in the dynamic view.
     * </p>
     *
     * @param endpoint the verified identity and advertised network address of one
     *                 successfully connected bootstrap peer
     */
    public synchronized void bootstrap(NodeEndpoint endpoint) {
        if (!blacklist.containsKey(endpoint.nodeId()) && !endpoint.nodeId().equals(self.nodeId()) && bootstrapped.add(endpoint.nodeId()) && view.size() < capacity
                && view.stream().noneMatch(p -> p.creator().nodeId().equals(endpoint.nodeId()))) {
            view.add(NodeDescriptor.fresh(endpoint, 0).transfer(self.nodeId()));
            updated(List.of());
        }
    }

    public synchronized Optional<Outgoing> executeCycle(long now) {
        cycle++;
        emit(SecureCyclonEvent.CYCLE, "");
        // Filter the peer samples in order to remove old ones
        history.values().removeIf(p -> p.timestamp() < now - historyAge);
        // Prevents a cycle execution too soon
        if (lastFresh >= 0 && now - lastFresh < interval) return Optional.empty();
        // When peers are sent, they are lost, even if the peer does not respond.
        if (pending != null) {
            replacePeers(pending.sent(), List.of());
            // Resolve the pending exchange before starting the new cycle
            pending = null;
        }
        if (view.isEmpty()) return Optional.empty();

        List<NodeDescriptor> before = List.copyOf(view);
        // PeerNet sorts descending, then polls last (last inserted wins equal timestamps)
        view.sort(Comparator.comparingLong(NodeDescriptor::timestamp).reversed());
        NodeDescriptor oldest = view.removeLast();

        List<NodeDescriptor> sent = selectPeers(oldest.creator().nodeId(), swapLength - 1);
        List<NodeDescriptor> descriptors = new ArrayList<>();
        // A fresh link to self will be sent too
        descriptors.add(NodeDescriptor.fresh(self, now).transfer(oldest.creator().nodeId()));
        // Add the receiver node as owner for the sent peers
        sent.forEach(p -> descriptors.add(p.transfer(oldest.creator().nodeId())));

        List<NodeDescriptor> samples = new ArrayList<>(view);
        samples.add(oldest);

        // Create the pending exchange using a random UUID as its identifier
        UUID id = new UUID(random.nextLong(), random.nextLong());
        pending = new Pending(oldest.creator().nodeId(), id, sent);
        // Not the cycle time
        lastFresh = now;
        updated(before);

        emit(SecureCyclonEvent.GOSSIP_SENT, "peerNodeId=" + oldest.creator().nodeId());

        return Optional.of(new Outgoing(oldest.creator(), id, new GossipExchange(descriptors, samples, proofs())));
    }

    // This method corresponds to the passive thread
    public synchronized GossipExchange handleSwapRequest(String sender, GossipExchange exchange, long now) {
        // Validate the incoming exchange and proof
        validateExchange(sender, exchange, true, now);
        List<ValidatedProof> receivedProofs = validateProofs(exchange.proofs(), now);

        // Detect inconsistencies and check respective proofs
        DetectedInconsistencies detected = detectInconsistencies(exchange);
        detected.proofs().forEach(this::acceptValidatedProof);
        if (detected.rejectExchange()) throw reject("Fresh-descriptor frequency exceeded");
        receivedProofs.forEach(this::acceptValidatedProof);

        List<NodeDescriptor> sent = selectPeers(sender, swapLength);
        List<NodeDescriptor> samples = List.copyOf(view);
        remember(exchange, detected.forkedDescriptorIds());
        // Replace descriptors in the view with descriptors from the incoming exchange
        replacePeers(sent, safeDescriptors(exchange.descriptors(), detected.forkedDescriptorIds()));

        emit(SecureCyclonEvent.GOSSIP_RECEIVED, "peerNodeId=" + sender);
        return new GossipExchange(sent.stream().map(p -> p.transfer(sender)).toList(), samples, proofs());
    }

    // This method corresponds to the active thread
    public synchronized void handleSwapResponse(String sender, UUID id, GossipExchange exchange, long now) {
        // Check that the sender is the correct one
        if (pending == null || !pending.partnerId().equals(sender) || !pending.id().equals(id))
            throw reject("Unexpected response");
        // Validate the incoming exchange and proof
        validateExchange(sender, exchange, false, now);
        List<ValidatedProof> receivedProofs = validateProofs(exchange.proofs(), now);

        // Detect inconsistencies and check respective proofs
        DetectedInconsistencies detected = detectInconsistencies(exchange);
        detected.proofs().forEach(this::acceptValidatedProof);
        if (detected.rejectExchange()) throw reject("Fresh-descriptor frequency exceeded");
        receivedProofs.forEach(this::acceptValidatedProof);

        remember(exchange, detected.forkedDescriptorIds());
        // Replace sent descriptors view with descriptors from the incoming exchange
        replacePeers(pending.sent(), safeDescriptors(exchange.descriptors(), detected.forkedDescriptorIds()));
        pending = null;
        emit(SecureCyclonEvent.GOSSIP_RECEIVED, "peerNodeId=" + sender);
    }

    // Select peers to send during a gossip exchange
    private List<NodeDescriptor> selectPeers(String receiver, int count) {
        // Filter out non-swappable links, links to the receiver, links that have been send and pending (passive thread)
        List<NodeDescriptor> eligible = new ArrayList<>(view.stream().filter(NodeDescriptor::swappable)
                .filter(p -> !p.creator().nodeId().equals(receiver))
                .filter(p -> pending == null || !pending.sent().contains(p)).toList());
        Collections.shuffle(eligible, random);
        return List.copyOf(eligible.subList(0, Math.min(count, eligible.size())));
    }

    // Performs various validations to determine the validity of an incoming gossip exchange
    private void validateExchange(String sender, GossipExchange exchange, boolean request, long now) {
        // Validate the incomming exchange object
        if (sender == null || sender.equals(self.nodeId()) || exchange == null
                || blacklist.containsKey(sender) || exchange.proofs().size() > MAX_PROOFS_PER_EXCHANGE
                || exchange.descriptors().size() > swapLength || exchange.samples().size() > capacity + 1)
            throw reject("Invalid exchange size or sender");

        // Validate incomming descriptors
        Set<NodeDescriptor> descriptors = new HashSet<>();
        for (NodeDescriptor p : exchange.descriptors()) {
            validatePeer(p, now);
            // Basic checks for swappability, self-reference, and duplicates
            if (!p.swappable() || p.creator().nodeId().equals(self.nodeId()) || !descriptors.add(p))
                throw reject("Non-swappable, self or duplicate exchange descriptor");
            int n = p.ownershipChain().size();
            // Ownership chain validation
            if (n < 2 || !p.ownershipChain().get(n - 1).equals(self.nodeId()) || !p.ownershipChain().get(n - 2).equals(sender))
                throw reject("Invalid ownership transfer");
            // Ownership chain checks for fresh descriptor creation
            if (p.ownershipChain().size() == 2 && (!request || !p.creator().nodeId().equals(sender)))
                throw reject("Illegal fresh descriptor creation");
        }
        exchange.samples().forEach(p -> validatePeer(p, now));
        // In case of a request, validate that the first owner is the sender, and that it has correct ownership chain size
        if (request && (exchange.descriptors().isEmpty() || !exchange.descriptors().getFirst().creator().nodeId().equals(sender)
                || exchange.descriptors().getFirst().ownershipChain().size() != 2))
            throw reject("Request requires one fresh sender descriptor first");
        if (request) {
            NodeDescriptor fresh = exchange.descriptors().getFirst();
            if (fresh.timestamp() == 0 || now - fresh.timestamp() > interval * 2
                    || history.containsKey(fresh.uniqueId())) throw reject("Stale or replayed fresh descriptor");
        }
    }

    private DetectedInconsistencies detectInconsistencies(GossipExchange exchange) {
        List<NodeDescriptor> evidence = new ArrayList<>(exchange.samples());
        evidence.addAll(exchange.descriptors());
        Map<String, NodeDescriptor> checked = new HashMap<>(history);
        Set<String> forkedDescriptorIds = new HashSet<>();
        List<ValidatedProof> proofs = new ArrayList<>();
        for (NodeDescriptor p : evidence) {
            if (forkedDescriptorIds.contains(p.uniqueId())) continue;
            NodeDescriptor prior = checked.get(p.uniqueId());
            if (prior != null && p.timestamp() != 0) {
                String culprit = ownershipForkCulprit(prior, p);
                if (culprit != null) {
                    forkedDescriptorIds.add(p.uniqueId());
                    proofs.add(new ValidatedProof(new ViolationProof(prior, p), culprit));
                    continue;
                }
            }
            for (NodeDescriptor old : checked.values()) {
                if (isFrequencyViolation(old, p)) {
                    proofs.add(new ValidatedProof(new ViolationProof(old, p), p.creator().nodeId()));
                    return new DetectedInconsistencies(Set.copyOf(forkedDescriptorIds),
                            List.copyOf(proofs), true);
                }
            }
            // Keep the descriptor with the longest ownership chain
            if (prior == null || prior.ownershipChain().size() < p.ownershipChain().size())
                checked.put(p.uniqueId(), p);
        }
        return new DetectedInconsistencies(Set.copyOf(forkedDescriptorIds), List.copyOf(proofs), false);
    }

    // Bunch of checks that validate a descriptor legitimancy
    private void validatePeer(NodeDescriptor p, long now) {
        if (p == null || p.creator() == null || p.timestamp() < 0 || p.timestamp() > now + interval
                || !NodeDescriptor.id(p.creator().nodeId(), p.timestamp()).equals(p.uniqueId())
                || p.ownershipChain().isEmpty() || p.ownershipChain().size() > 1024 || !p.ownershipChain().getFirst().equals(p.creator().nodeId())
                || p.ownershipChain().stream().anyMatch(id -> !id.matches("[0-9a-f]{64}")))
            throw reject("Invalid descriptor identity, timestamp or ownership chain");
    }

    // Add newly observed peers to the history
    private void remember(GossipExchange exchange, Set<String> forkedDescriptorIds) {
        List<NodeDescriptor> all = new ArrayList<>(exchange.samples());
        all.addAll(exchange.descriptors());
        all.stream().filter(p -> !forkedDescriptorIds.contains(p.uniqueId()))
                .filter(p -> !blacklist.containsKey(p.creator().nodeId()))
                // If peer is already known, keep the one with the longest ownership chain
                .forEach(p -> history.merge(p.uniqueId(), p,
                        (a, b) -> a.ownershipChain().size() >= b.ownershipChain().size() ? a : b));
    }

    private List<NodeDescriptor> safeDescriptors(List<NodeDescriptor> descriptors, Set<String> forkedDescriptorIds) {
        return descriptors.stream().filter(p -> !forkedDescriptorIds.contains(p.uniqueId()))
                .filter(p -> !blacklist.containsKey(p.creator().nodeId())).toList();
    }

    private void replacePeers(List<NodeDescriptor> sent, List<NodeDescriptor> received) {
        List<NodeDescriptor> before = List.copyOf(view);
        List<NodeDescriptor> nonSwappable = view.stream().filter(p -> !p.swappable()).toList();
        for (NodeDescriptor p : received) {
            if (blacklist.containsKey(p.creator().nodeId())) continue;
            // Match PeerNet: distinct descriptors may point to the same creator node.
            if (capacity > view.size() - (sent.size() + nonSwappable.size())) {
                view.add(p);
            } else {
                break;
            }
        }

        List<NodeDescriptor> notRemoved = new ArrayList<>(sent);
        for (NodeDescriptor p : sent) {
            if (view.size() > capacity) {
                view.remove(p);
                notRemoved.remove(p);
            }
        }
        for (NodeDescriptor p : nonSwappable) if (view.size() > capacity) view.remove(p);
        for (NodeDescriptor p : notRemoved) {
            int index = view.indexOf(p);
            if (index != -1) view.set(index, view.get(index).retained());
        }
        updated(before);
        assert view.size() <= capacity : "SecureCyclon view exceeds its configured capacity";
    }

    /**
     * Returns retained blacklist proofs for inclusion in each regular gossip exchange.
     * This provides eventual proof propagation to nodes that missed the initial
     * fan-out or rejoined later. It intentionally permits repeated network
     * transmission of already-known proofs; receivers suppress re-acceptance.
     */
    private List<ViolationProof> proofs() {
        return blacklist.values().stream().limit(MAX_PROOFS_PER_EXCHANGE).toList();
    }

    public synchronized List<ViolationProof> drainProofs() {
        var result = List.copyOf(newProofs);
        newProofs.clear();
        return result;
    }

    public synchronized void acceptProof(ViolationProof proof, long now) {
        acceptValidatedProof(validateProof(proof, now));
    }

    private List<ValidatedProof> validateProofs(List<ViolationProof> proofs, long now) {
        return proofs.stream().map(proof -> validateProof(proof, now)).toList();
    }

    private ValidatedProof validateProof(ViolationProof proof, long now) {
        // Validate the incoming proof object
        if (proof == null) throw reject("Missing inconsistency proof");
        NodeDescriptor a = proof.first(), b = proof.second();
        validatePeer(a, now);
        validatePeer(b, now);
        if (a.timestamp() == 0 || b.timestamp() == 0)
            throw reject("Bootstrap is exempt from inconsistency detection");

        String offender = ownershipForkCulprit(a, b);
        if (offender == null && isFrequencyViolation(a, b)) offender = a.creator().nodeId();
        if (offender == null) throw reject("Proof contains no ownership or frequency violation");
        return new ValidatedProof(proof, offender);
    }

    // Checks for illegal change of ownership
    private String ownershipForkCulprit(NodeDescriptor a, NodeDescriptor b) {
        if (!a.uniqueId().equals(b.uniqueId())) return null;
        if (!a.creator().equals(b.creator())) throw reject("Inconsistent endpoint for descriptor");
        int common = Math.min(a.ownershipChain().size(), b.ownershipChain().size());
        for (int i = 1; i < common; i++)
            if (!a.ownershipChain().get(i).equals(b.ownershipChain().get(i)))
                return a.ownershipChain().get(i - 1);
        return null;
    }

    // Checks for illegal frequency violations
    private boolean isFrequencyViolation(NodeDescriptor a, NodeDescriptor b) {
        return a.timestamp() != 0 && b.timestamp() != 0
                && a.creator().nodeId().equals(b.creator().nodeId())
                && !a.uniqueId().equals(b.uniqueId())
                && Math.abs(a.timestamp() - b.timestamp()) < interval;
    }

    // Add the proof to the proof set, and remove it from view, and from sent peers
    private void acceptValidatedProof(ValidatedProof validated) {
        ViolationProof proof = validated.proof();
        String offender = validated.culprit();
        if (blacklist.putIfAbsent(offender, proof) == null) {
            newProofs.add(proof);
            var before = List.copyOf(view);
            view.removeIf(p -> p.creator().nodeId().equals(offender));
            if (pending != null) pending = new Pending(pending.partnerId(), pending.id(),
                    pending.sent().stream().filter(p -> !p.creator().nodeId().equals(offender)).toList());
            updated(before);
        }
    }

    private IllegalArgumentException reject(String reason) {
        emit(SecureCyclonEvent.EXCHANGE_REJECTED, "reason=" + reason);
        return new IllegalArgumentException(reason);
    }

    private void updated(List<NodeDescriptor> before) {
        for (NodeDescriptor p : before)
            if (view.stream().noneMatch(n -> n.creator().nodeId().equals(p.creator().nodeId())))
                emit(SecureCyclonEvent.PEER_REMOVED, "peerNodeId=" + p.creator().nodeId() + " reason=redeemed_or_replaced");
        for (NodeDescriptor p : view)
            if (before.stream().noneMatch(n -> n.creator().nodeId().equals(p.creator().nodeId())))
                emit(SecureCyclonEvent.PEER_DISCOVERED, "peerNodeId=" + p.creator().nodeId());
        emit(SecureCyclonEvent.VIEW_UPDATED, "view=" + view());
    }

    private void emit(SecureCyclonEvent event, String fields) {
        log.accept(event + " nodeId=" + self.nodeId() + " viewSize=" + view.size() + " cycle=" + cycle + " " + fields);
    }

    public synchronized List<NodeDescriptor> descriptors() {
        return List.copyOf(view);
    }

    @Override
    public synchronized List<NodeEndpoint> view() {
        return view.stream().map(NodeDescriptor::creator).toList();
    }

    @Override
    public synchronized Optional<NodeEndpoint> randomPeer() {
        return view.isEmpty() ? Optional.empty() : Optional.of(view.get(random.nextInt(view.size())).creator());
    }
}
