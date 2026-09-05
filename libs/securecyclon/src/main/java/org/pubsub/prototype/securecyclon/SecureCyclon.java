package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.sampling.PeerSamplingService;
import java.util.*;
import java.util.function.Consumer;

/** PeerNet's non-tit-for-tat state machine, serialized independently of any transport. */
public final class SecureCyclon implements PeerSamplingService {
    public record Outgoing(PeerDescriptor peer, UUID requestId, Exchange exchange) {}
    private record Pending(String peerId, UUID id, List<SecureLink> sent) {}
    private final PeerDescriptor self;
    private final int capacity, swapLength;
    private final long interval, historyAge;
    private final Random random;
    private final Consumer<String> log;
    private final List<SecureLink> neighbors = new ArrayList<>();
    private final Map<String, SecureLink> history = new HashMap<>();
    private final Set<String> bootstrapped = new HashSet<>();
    private final Map<String, LinkProof> blacklist = new LinkedHashMap<>();
    private final List<LinkProof> newReports = new ArrayList<>();
    private Pending pending;
    private long lastFresh = -1, cycle;

    public SecureCyclon(PeerDescriptor self, int capacity, int swapLength, long interval,
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
    public synchronized void bootstrap(PeerDescriptor peer) {
        if (!blacklist.containsKey(peer.nodeId()) && !peer.nodeId().equals(self.nodeId()) && bootstrapped.add(peer.nodeId()) && neighbors.size() < capacity
                && neighbors.stream().noneMatch(p -> p.peer().nodeId().equals(peer.nodeId()))) {
            neighbors.add(SecureLink.fresh(peer, 0).transfer(self.nodeId()));
            updated(List.of());
        }
    }

    public synchronized Optional<Outgoing> cycle(long now) {
        cycle++;
        emit("SECURECYCLON_CYCLE", "");
        history.values().removeIf(p -> p.timestamp() < now - historyAge);
        if (lastFresh >= 0 && now - lastFresh < interval) return Optional.empty();
        // A timed-out exchange transfers ownership too: never resend its links as swappable.
        if (pending != null) { replace(pending.sent(), List.of()); pending = null; }
        if (neighbors.isEmpty()) return Optional.empty();
        List<SecureLink> before = List.copyOf(neighbors);
        // PeerNet sorts descending, then polls last (last inserted wins equal timestamps).
        neighbors.sort(Comparator.comparingLong(SecureLink::timestamp).reversed());
        SecureLink oldest = neighbors.removeLast();
        List<SecureLink> sent = select(oldest.peer().nodeId(), swapLength - 1);
        List<SecureLink> links = new ArrayList<>();
        links.add(SecureLink.fresh(self, now).transfer(oldest.peer().nodeId()));
        sent.forEach(p -> links.add(p.transfer(oldest.peer().nodeId())));
        List<SecureLink> samples = new ArrayList<>(neighbors);
        samples.add(oldest);
        UUID id = new UUID(random.nextLong(), random.nextLong());
        pending = new Pending(oldest.peer().nodeId(), id, sent);
        lastFresh = now;
        updated(before);
        emit("SECURECYCLON_GOSSIP_SENT", "peerNodeId=" + oldest.peer().nodeId());
        return Optional.of(new Outgoing(oldest.peer(), id, new Exchange(links, samples, reports())));
    }

    public synchronized Exchange request(String sender, Exchange exchange, long now) {
        validate(sender, exchange, true, now);
        exchange.reports().forEach(p -> acceptReport(p, now));
        List<SecureLink> sent = select(sender, swapLength);
        List<SecureLink> samples = List.copyOf(neighbors);
        remember(exchange);
        replace(sent, exchange.links());
        emit("SECURECYCLON_GOSSIP_RECEIVED", "peerNodeId=" + sender);
        return new Exchange(sent.stream().map(p -> p.transfer(sender)).toList(), samples, reports());
    }

    public synchronized void response(String sender, UUID id, Exchange exchange, long now) {
        if (pending == null || !pending.peerId().equals(sender) || !pending.id().equals(id))
            throw reject("Unexpected response");
        validate(sender, exchange, false, now);
        exchange.reports().forEach(p -> acceptReport(p, now));
        remember(exchange);
        replace(pending.sent(), exchange.links());
        pending = null;
        emit("SECURECYCLON_GOSSIP_RECEIVED", "peerNodeId=" + sender);
    }

    private List<SecureLink> select(String receiver, int count) {
        List<SecureLink> eligible = new ArrayList<>(neighbors.stream().filter(SecureLink::swappable)
                .filter(p -> !p.peer().nodeId().equals(receiver))
                .filter(p -> pending == null || !pending.sent().contains(p)).toList());
        Collections.shuffle(eligible, random);
        return List.copyOf(eligible.subList(0, Math.min(count, eligible.size())));
    }

    private void validate(String sender, Exchange exchange, boolean request, long now) {
        if (sender == null || sender.equals(self.nodeId()) || exchange == null
                || blacklist.containsKey(sender) || exchange.reports().size() > 20
                || exchange.links().size() > swapLength || exchange.samples().size() > capacity + 1)
            throw reject("Invalid exchange size or sender");
        exchange.links().forEach(p -> validateLink(p, now));
        if (request && (exchange.links().isEmpty() || !exchange.links().getFirst().peer().nodeId().equals(sender)
                || exchange.links().getFirst().owners().size() != 2))
            throw reject("Request requires one fresh sender link first");
        exchange.reports().forEach(p -> culprit(p, now));
        Set<String> ids = new HashSet<>();
        Map<String, SecureLink> checked = new HashMap<>(history);
        for (SecureLink p : exchange.links()) {
            validateLink(p, now);
            if (!p.swappable() || p.peer().nodeId().equals(self.nodeId()) || !ids.add(p.peer().nodeId()))
                throw reject("Non-swappable, self or duplicate exchange entry");
            int n = p.owners().size();
            if (n < 2 || !p.owners().get(n - 1).equals(self.nodeId()) || !p.owners().get(n - 2).equals(sender))
                throw reject("Invalid ownership transfer");
            if (p.owners().size() == 2 && (!request || !p.peer().nodeId().equals(sender)))
                throw reject("Illegal fresh-link creation");
        }
        List<SecureLink> evidence = new ArrayList<>(exchange.samples()); evidence.addAll(exchange.links());
        for (SecureLink p : evidence) {
            validateLink(p, now);
            SecureLink prior = checked.get(p.uniqueId());
            if (prior != null && p.timestamp() != 0) {
                int common = Math.min(prior.owners().size(), p.owners().size());
                if (!prior.peer().equals(p.peer())) throw reject("Inconsistent endpoint for link");
                if (!prior.owners().subList(0, common).equals(p.owners().subList(0, common))) {
                    acceptReport(new LinkProof(prior, p), now);
                    throw reject("Ownership fork");
                }
            }
            for (SecureLink old : checked.values()) {
                if (p.timestamp() != 0 && old.timestamp() != 0 && old.peer().nodeId().equals(p.peer().nodeId())
                        && !old.uniqueId().equals(p.uniqueId()) && Math.abs(old.timestamp() - p.timestamp()) < interval) {
                    acceptReport(new LinkProof(old, p), now);
                    throw reject("Fresh-link frequency exceeded");
                }
            }
            if (prior == null || prior.owners().size() < p.owners().size()) checked.put(p.uniqueId(), p);
        }
        if (request) {
            SecureLink fresh = exchange.links().getFirst();
            if (fresh.timestamp() == 0 || now - fresh.timestamp() > interval * 2
                    || history.containsKey(fresh.uniqueId())) throw reject("Stale or replayed fresh link");
        }
    }

    private void validateLink(SecureLink p, long now) {
        if (p == null || p.peer() == null || p.timestamp() < 0 || p.timestamp() > now + interval
                || !SecureLink.id(p.peer().nodeId(), p.timestamp()).equals(p.uniqueId())
                || p.owners().isEmpty() || p.owners().size() > 1024 || !p.owners().getFirst().equals(p.peer().nodeId())
                || p.owners().stream().anyMatch(id -> !id.matches("[0-9a-f]{64}")))
            throw reject("Invalid link identity, timestamp or ownership chain");
    }

    private void remember(Exchange exchange) {
        List<SecureLink> all = new ArrayList<>(exchange.samples()); all.addAll(exchange.links());
        all.forEach(p -> history.merge(p.uniqueId(), p, (a, b) -> a.owners().size() >= b.owners().size() ? a : b));
    }

    private void replace(List<SecureLink> sent, List<SecureLink> received) {
        List<SecureLink> before = List.copyOf(neighbors);
        List<SecureLink> nonSwappable = neighbors.stream().filter(p -> !p.swappable()).toList();
        for (SecureLink p : received) {
            if (blacklist.containsKey(p.peer().nodeId())) continue;
            // Phase 0.4 requires unique nodeIds; newer owned links supersede an existing descriptor.
            SecureLink existing = neighbors.stream().filter(n -> n.peer().nodeId().equals(p.peer().nodeId())).findFirst().orElse(null);
            if (existing != null) {
                if (existing.timestamp() > p.timestamp() || (existing.timestamp() == p.timestamp()
                        && existing.swappable() && existing.owners().size() >= p.owners().size())) continue;
                neighbors.remove(existing);
            }
            long removable = neighbors.stream().filter(n -> sent.contains(n) || nonSwappable.contains(n)).count();
            if (neighbors.size() - removable < capacity) neighbors.add(p);
        }
        for (SecureLink p : sent) if (neighbors.size() > capacity) neighbors.remove(p);
        for (SecureLink p : nonSwappable) if (neighbors.size() > capacity) neighbors.remove(p);
        for (int i = 0; i < neighbors.size(); i++) if (sent.contains(neighbors.get(i))) neighbors.set(i, neighbors.get(i).retained());
        updated(before);
    }

    private List<LinkProof> reports() { return blacklist.values().stream().limit(20).toList(); }

    public synchronized List<LinkProof> drainReports() {
        var result = List.copyOf(newReports); newReports.clear(); return result;
    }

    public synchronized void acceptReport(LinkProof proof, long now) {
        String offender = culprit(proof, now);
        if (blacklist.putIfAbsent(offender, proof) == null) {
            newReports.add(proof);
            var before = List.copyOf(neighbors);
            neighbors.removeIf(p -> p.peer().nodeId().equals(offender));
            if (pending != null) pending = new Pending(pending.peerId(), pending.id(),
                    pending.sent().stream().filter(p -> !p.peer().nodeId().equals(offender)).toList());
            updated(before);
        }
    }

    private String culprit(LinkProof proof, long now) {
        if (proof == null) throw reject("Missing inconsistency proof");
        SecureLink a = proof.first(), b = proof.second();
        validateLink(a, now); validateLink(b, now);
        if (a.timestamp() == 0 || b.timestamp() == 0) throw reject("Bootstrap is exempt from inconsistency detection");
        if (a.uniqueId().equals(b.uniqueId())) {
            int common = Math.min(a.owners().size(), b.owners().size());
            for (int i = 1; i < common; i++)
                if (!a.owners().get(i).equals(b.owners().get(i))) return a.owners().get(i - 1);
        } else if (a.peer().nodeId().equals(b.peer().nodeId()) && Math.abs(a.timestamp() - b.timestamp()) < interval) {
            return a.peer().nodeId();
        }
        throw reject("Report contains no ownership or frequency inconsistency");
    }

    private IllegalArgumentException reject(String reason) {
        emit("SECURECYCLON_EXCHANGE_REJECTED", "reason=" + reason);
        return new IllegalArgumentException(reason);
    }
    private void updated(List<SecureLink> before) {
        for (SecureLink p : before) if (neighbors.stream().noneMatch(n -> n.peer().nodeId().equals(p.peer().nodeId())))
            emit("SECURECYCLON_PEER_REMOVED", "peerNodeId=" + p.peer().nodeId() + " reason=redeemed_or_replaced");
        for (SecureLink p : neighbors) if (before.stream().noneMatch(n -> n.peer().nodeId().equals(p.peer().nodeId())))
            emit("SECURECYCLON_PEER_DISCOVERED", "peerNodeId=" + p.peer().nodeId());
        emit("SECURECYCLON_VIEW_UPDATED", "view=" + view());
    }
    private void emit(String event, String fields) {
        log.accept(event + " nodeId=" + self.nodeId() + " viewSize=" + neighbors.size() + " cycle=" + cycle + " " + fields);
    }
    public synchronized List<SecureLink> links() { return List.copyOf(neighbors); }
    @Override public synchronized List<PeerDescriptor> view() { return neighbors.stream().map(SecureLink::peer).toList(); }
    @Override public synchronized Optional<PeerDescriptor> randomPeer() {
        return neighbors.isEmpty() ? Optional.empty() : Optional.of(neighbors.get(random.nextInt(neighbors.size())).peer());
    }
}
