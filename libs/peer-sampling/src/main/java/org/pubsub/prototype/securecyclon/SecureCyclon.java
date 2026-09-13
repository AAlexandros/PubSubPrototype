package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.sampling.PeerSamplingService;
import java.util.*;
import java.util.function.Consumer;

/** PeerNet's non-tit-for-tat state machine, serialized independently of any transport. */
public final class SecureCyclon implements PeerSamplingService {
    public record Outgoing(NodeEndpoint partner, UUID requestId, GossipExchange exchange) {}
    private record Pending(String partnerId, UUID id, List<NodeDescriptor> sent) {}
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
        if (capacity < 1 || capacity > 256 || swapLength < 1 || swapLength > capacity
                || interval < 1 || interval > 86_400_000 || ageThreshold < 0 || ageThreshold > 10000)
            throw new IllegalArgumentException("Invalid SecureCyclon configuration");
        this.self = Objects.requireNonNull(self);
        this.capacity = capacity; this.swapLength = swapLength; this.interval = interval;
        this.historyAge = Math.multiplyExact(interval, (long) capacity + ageThreshold);
        this.random = Objects.requireNonNull(random); this.log = Objects.requireNonNull(log);
    }

    /** A configured seed is admitted only once, after HELLO resolved its identity and endpoint. */
    public synchronized void bootstrap(NodeEndpoint endpoint) {
        if (!blacklist.containsKey(endpoint.nodeId()) && !endpoint.nodeId().equals(self.nodeId()) && bootstrapped.add(endpoint.nodeId()) && view.size() < capacity
                && view.stream().noneMatch(p -> p.creator().nodeId().equals(endpoint.nodeId()))) {
            view.add(NodeDescriptor.fresh(endpoint, 0).transfer(self.nodeId()));
            updated(List.of());
        }
    }

    public synchronized Optional<Outgoing> cycle(long now) {
        cycle++;
        emit("SECURECYCLON_CYCLE", "");
        history.values().removeIf(p -> p.timestamp() < now - historyAge);
        if (lastFresh >= 0 && now - lastFresh < interval) return Optional.empty();
        // A timed-out exchange transfers ownership too: never resend its descriptors as swappable.
        if (pending != null) { replace(pending.sent(), List.of()); pending = null; }
        if (view.isEmpty()) return Optional.empty();
        List<NodeDescriptor> before = List.copyOf(view);
        // PeerNet sorts descending, then polls last (last inserted wins equal timestamps).
        view.sort(Comparator.comparingLong(NodeDescriptor::timestamp).reversed());
        NodeDescriptor oldest = view.removeLast();
        List<NodeDescriptor> sent = select(oldest.creator().nodeId(), swapLength - 1);
        List<NodeDescriptor> descriptors = new ArrayList<>();
        descriptors.add(NodeDescriptor.fresh(self, now).transfer(oldest.creator().nodeId()));
        sent.forEach(p -> descriptors.add(p.transfer(oldest.creator().nodeId())));
        List<NodeDescriptor> samples = new ArrayList<>(view);
        samples.add(oldest);
        UUID id = new UUID(random.nextLong(), random.nextLong());
        pending = new Pending(oldest.creator().nodeId(), id, sent);
        lastFresh = now;
        updated(before);
        emit("SECURECYCLON_GOSSIP_SENT", "peerNodeId=" + oldest.creator().nodeId());
        return Optional.of(new Outgoing(oldest.creator(), id, new GossipExchange(descriptors, samples, proofs())));
    }

    public synchronized GossipExchange request(String sender, GossipExchange exchange, long now) {
        validate(sender, exchange, true, now);
        exchange.proofs().forEach(p -> acceptProof(p, now));
        List<NodeDescriptor> sent = select(sender, swapLength);
        List<NodeDescriptor> samples = List.copyOf(view);
        remember(exchange);
        replace(sent, exchange.descriptors());
        emit("SECURECYCLON_GOSSIP_RECEIVED", "peerNodeId=" + sender);
        return new GossipExchange(sent.stream().map(p -> p.transfer(sender)).toList(), samples, proofs());
    }

    public synchronized void response(String sender, UUID id, GossipExchange exchange, long now) {
        if (pending == null || !pending.partnerId().equals(sender) || !pending.id().equals(id))
            throw reject("Unexpected response");
        validate(sender, exchange, false, now);
        exchange.proofs().forEach(p -> acceptProof(p, now));
        remember(exchange);
        replace(pending.sent(), exchange.descriptors());
        pending = null;
        emit("SECURECYCLON_GOSSIP_RECEIVED", "peerNodeId=" + sender);
    }

    private List<NodeDescriptor> select(String receiver, int count) {
        List<NodeDescriptor> eligible = new ArrayList<>(view.stream().filter(NodeDescriptor::swappable)
                .filter(p -> !p.creator().nodeId().equals(receiver))
                .filter(p -> pending == null || !pending.sent().contains(p)).toList());
        Collections.shuffle(eligible, random);
        return List.copyOf(eligible.subList(0, Math.min(count, eligible.size())));
    }

    private void validate(String sender, GossipExchange exchange, boolean request, long now) {
        if (sender == null || sender.equals(self.nodeId()) || exchange == null
                || blacklist.containsKey(sender) || exchange.proofs().size() > 20
                || exchange.descriptors().size() > swapLength || exchange.samples().size() > capacity + 1)
            throw reject("Invalid exchange size or sender");
        exchange.descriptors().forEach(p -> validateDescriptor(p, now));
        if (request && (exchange.descriptors().isEmpty() || !exchange.descriptors().getFirst().creator().nodeId().equals(sender)
                || exchange.descriptors().getFirst().ownershipChain().size() != 2))
            throw reject("Request requires one fresh sender descriptor first");
        exchange.proofs().forEach(p -> culprit(p, now));
        Set<String> ids = new HashSet<>();
        Map<String, NodeDescriptor> checked = new HashMap<>(history);
        for (NodeDescriptor p : exchange.descriptors()) {
            validateDescriptor(p, now);
            if (!p.swappable() || p.creator().nodeId().equals(self.nodeId()) || !ids.add(p.creator().nodeId()))
                throw reject("Non-swappable, self or duplicate exchange descriptor");
            int n = p.ownershipChain().size();
            if (n < 2 || !p.ownershipChain().get(n - 1).equals(self.nodeId()) || !p.ownershipChain().get(n - 2).equals(sender))
                throw reject("Invalid ownership transfer");
            if (p.ownershipChain().size() == 2 && (!request || !p.creator().nodeId().equals(sender)))
            throw reject("Illegal fresh descriptor creation");
        }
        List<NodeDescriptor> evidence = new ArrayList<>(exchange.samples()); evidence.addAll(exchange.descriptors());
        for (NodeDescriptor p : evidence) {
            validateDescriptor(p, now);
            NodeDescriptor prior = checked.get(p.uniqueId());
            if (prior != null && p.timestamp() != 0) {
                int common = Math.min(prior.ownershipChain().size(), p.ownershipChain().size());
                if (!prior.creator().equals(p.creator())) throw reject("Inconsistent endpoint for descriptor");
                if (!prior.ownershipChain().subList(0, common).equals(p.ownershipChain().subList(0, common))) {
                    acceptProof(new ViolationProof(prior, p), now);
                    throw reject("Ownership fork");
                }
            }
            for (NodeDescriptor old : checked.values()) {
                if (p.timestamp() != 0 && old.timestamp() != 0 && old.creator().nodeId().equals(p.creator().nodeId())
                        && !old.uniqueId().equals(p.uniqueId()) && Math.abs(old.timestamp() - p.timestamp()) < interval) {
                    acceptProof(new ViolationProof(old, p), now);
                    throw reject("Fresh-descriptor frequency exceeded");
                }
            }
            if (prior == null || prior.ownershipChain().size() < p.ownershipChain().size()) checked.put(p.uniqueId(), p);
        }
        if (request) {
            NodeDescriptor fresh = exchange.descriptors().getFirst();
            if (fresh.timestamp() == 0 || now - fresh.timestamp() > interval * 2
                    || history.containsKey(fresh.uniqueId())) throw reject("Stale or replayed fresh descriptor");
        }
    }

    private void validateDescriptor(NodeDescriptor p, long now) {
        if (p == null || p.creator() == null || p.timestamp() < 0 || p.timestamp() > now + interval
                || !NodeDescriptor.id(p.creator().nodeId(), p.timestamp()).equals(p.uniqueId())
                || p.ownershipChain().isEmpty() || p.ownershipChain().size() > 1024 || !p.ownershipChain().getFirst().equals(p.creator().nodeId())
                || p.ownershipChain().stream().anyMatch(id -> !id.matches("[0-9a-f]{64}")))
            throw reject("Invalid descriptor identity, timestamp or ownership chain");
    }

    private void remember(GossipExchange exchange) {
        List<NodeDescriptor> all = new ArrayList<>(exchange.samples()); all.addAll(exchange.descriptors());
        all.forEach(p -> history.merge(p.uniqueId(), p, (a, b) -> a.ownershipChain().size() >= b.ownershipChain().size() ? a : b));
    }

    private void replace(List<NodeDescriptor> sent, List<NodeDescriptor> received) {
        List<NodeDescriptor> before = List.copyOf(view);
        List<NodeDescriptor> nonSwappable = view.stream().filter(p -> !p.swappable()).toList();
        for (NodeDescriptor p : received) {
            if (blacklist.containsKey(p.creator().nodeId())) continue;
            // Phase 0.4 requires unique nodeIds; newer owned descriptors supersede an existing descriptor.
            NodeDescriptor existing = view.stream().filter(n -> n.creator().nodeId().equals(p.creator().nodeId())).findFirst().orElse(null);
            if (existing != null) {
                if (existing.timestamp() > p.timestamp() || (existing.timestamp() == p.timestamp()
                        && existing.swappable() && existing.ownershipChain().size() >= p.ownershipChain().size())) continue;
                view.remove(existing);
            }
            long removable = view.stream().filter(n -> sent.contains(n) || nonSwappable.contains(n)).count();
            if (view.size() - removable < capacity) view.add(p);
        }
        for (NodeDescriptor p : sent) if (view.size() > capacity) view.remove(p);
        for (NodeDescriptor p : nonSwappable) if (view.size() > capacity) view.remove(p);
        for (int i = 0; i < view.size(); i++) if (sent.contains(view.get(i))) view.set(i, view.get(i).retained());
        updated(before);
    }

    private List<ViolationProof> proofs() { return blacklist.values().stream().limit(20).toList(); }

    public synchronized List<ViolationProof> drainProofs() {
        var result = List.copyOf(newProofs); newProofs.clear(); return result;
    }

    public synchronized void acceptProof(ViolationProof proof, long now) {
        String offender = culprit(proof, now);
        if (blacklist.putIfAbsent(offender, proof) == null) {
            newProofs.add(proof);
            var before = List.copyOf(view);
            view.removeIf(p -> p.creator().nodeId().equals(offender));
            if (pending != null) pending = new Pending(pending.partnerId(), pending.id(),
                    pending.sent().stream().filter(p -> !p.creator().nodeId().equals(offender)).toList());
            updated(before);
        }
    }

    private String culprit(ViolationProof proof, long now) {
        if (proof == null) throw reject("Missing inconsistency proof");
        NodeDescriptor a = proof.first(), b = proof.second();
        validateDescriptor(a, now); validateDescriptor(b, now);
        if (a.timestamp() == 0 || b.timestamp() == 0) throw reject("Bootstrap is exempt from inconsistency detection");
        if (a.uniqueId().equals(b.uniqueId())) {
            int common = Math.min(a.ownershipChain().size(), b.ownershipChain().size());
            for (int i = 1; i < common; i++)
                if (!a.ownershipChain().get(i).equals(b.ownershipChain().get(i))) return a.ownershipChain().get(i - 1);
        } else if (a.creator().nodeId().equals(b.creator().nodeId()) && Math.abs(a.timestamp() - b.timestamp()) < interval) {
            return a.creator().nodeId();
        }
        throw reject("Proof contains no ownership or frequency violation");
    }

    private IllegalArgumentException reject(String reason) {
        emit("SECURECYCLON_EXCHANGE_REJECTED", "reason=" + reason);
        return new IllegalArgumentException(reason);
    }
    private void updated(List<NodeDescriptor> before) {
        for (NodeDescriptor p : before) if (view.stream().noneMatch(n -> n.creator().nodeId().equals(p.creator().nodeId())))
            emit("SECURECYCLON_PEER_REMOVED", "peerNodeId=" + p.creator().nodeId() + " reason=redeemed_or_replaced");
        for (NodeDescriptor p : view) if (before.stream().noneMatch(n -> n.creator().nodeId().equals(p.creator().nodeId())))
            emit("SECURECYCLON_PEER_DISCOVERED", "peerNodeId=" + p.creator().nodeId());
        emit("SECURECYCLON_VIEW_UPDATED", "view=" + view());
    }
    private void emit(String event, String fields) {
        log.accept(event + " nodeId=" + self.nodeId() + " viewSize=" + view.size() + " cycle=" + cycle + " " + fields);
    }
    public synchronized List<NodeDescriptor> descriptors() { return List.copyOf(view); }
    @Override public synchronized List<NodeEndpoint> view() { return view.stream().map(NodeDescriptor::creator).toList(); }
    @Override public synchronized Optional<NodeEndpoint> randomPeer() {
        return view.isEmpty() ? Optional.empty() : Optional.of(view.get(random.nextInt(view.size())).creator());
    }
}
