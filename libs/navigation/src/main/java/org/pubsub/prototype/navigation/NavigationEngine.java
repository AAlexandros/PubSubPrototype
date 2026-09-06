package org.pubsub.prototype.navigation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Vicinity gossip state machine, serialized independently of any transport (mirrors SecureCyclon's shape). */
public final class NavigationEngine {
    public static final int PROTOCOL_VERSION = 1;

    public record Outgoing(NavigationPeerDescriptor peer, UUID requestId, NavigationExchange exchange) {
    }

    private final String nodeId;
    private final String host;
    private final int port;
    private final int capacity;
    private final int base;
    private final long staleAfterMs;
    private final Random random;
    private final Consumer<String> log;
    private final NavigationView view;
    private final Set<String> subscriptions = new LinkedHashSet<>();
    private TopicOrdering ordering = TopicOrdering.of(Set.of());
    private Set<Integer> fingerTopics = Set.of();
    private long cycle;

    public NavigationEngine(String nodeId, String host, int port, Collection<String> initialSubscriptions,
                             int capacity, int base, long staleAfterMs, Random random, Consumer<String> log) {
        if (capacity < 1 || capacity > 256 || base < 2 || base > 64 || staleAfterMs < 1) {
            throw new IllegalArgumentException("Invalid navigation configuration");
        }
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.capacity = capacity;
        this.base = base;
        this.staleAfterMs = staleAfterMs;
        this.random = Objects.requireNonNull(random, "random");
        this.log = Objects.requireNonNull(log, "log");
        this.view = new NavigationView(capacity);
        subscriptions.addAll(initialSubscriptions);
        recomputeFingerTopics();
    }

    /** Recomputes topic ordinals; ordinal meaning shifts when the active topic set changes, so the view is cleared. */
    public synchronized void updateOrdering(TopicOrdering newOrdering) {
        if (newOrdering.equals(ordering)) {
            return;
        }
        ordering = newOrdering;
        view.clear();
        recomputeFingerTopics();
    }

    public synchronized void subscribe(String topicId) {
        if (subscriptions.add(topicId)) {
            recomputeFingerTopics();
        }
    }

    public synchronized void unsubscribe(String topicId) {
        if (subscriptions.remove(topicId)) {
            recomputeFingerTopics();
        }
    }

    public synchronized Set<String> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    public synchronized Set<Integer> fingerTopics() {
        return Set.copyOf(fingerTopics);
    }

    public synchronized TopicOrdering ordering() {
        return ordering;
    }

    public synchronized Map<Integer, List<NavigationPeerDescriptor>> view() {
        return view.snapshot();
    }

    /** A raw SecureCyclon sample enters the candidate pool with unknown subscriptions until learned via gossip. */
    public synchronized void ingestSample(String peerNodeId, String peerHost, int peerPort, long now) {
        if (peerNodeId.equals(nodeId)) {
            return;
        }
        NavigationPeerDescriptor candidate = new NavigationPeerDescriptor(peerNodeId, peerHost, peerPort, List.of(), now);
        for (int target : fingerTopics) {
            view.offer(target, candidate, ordering, nodeId);
        }
    }

    public synchronized Optional<Outgoing> cycle(long now) {
        cycle++;
        for (String removed : view.pruneStale(now, staleAfterMs)) {
            emit("NAVIGATION_PEER_REMOVED", "peerNodeId=" + removed);
        }
        emit("NAVIGATION_CYCLE", "fingerTopics=" + fingerTopics.size());
        List<NavigationPeerDescriptor> pool = view.flatten();
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        NavigationPeerDescriptor partner = pool.get(random.nextInt(pool.size()));
        List<NavigationPeerDescriptor> candidates = selectCandidatesFor(partner);
        NavigationPeerDescriptor self = selfDescriptor(now);
        UUID requestId = new UUID(random.nextLong(), random.nextLong());
        emit("NAVIGATION_GOSSIP_SENT", "peerNodeId=" + partner.nodeId() + " candidates=" + candidates.size());
        return Optional.of(new Outgoing(partner, requestId,
                new NavigationExchange(self, self.subscribedTopicIds(), candidates, PROTOCOL_VERSION)));
    }

    public synchronized NavigationExchange request(String sender, NavigationExchange incoming, long now) {
        mergeExchange(incoming, now);
        List<NavigationPeerDescriptor> candidates = selectCandidatesFor(incoming.senderDescriptor());
        emit("NAVIGATION_GOSSIP_RECEIVED", "peerNodeId=" + sender);
        NavigationPeerDescriptor self = selfDescriptor(now);
        return new NavigationExchange(self, self.subscribedTopicIds(), candidates, PROTOCOL_VERSION);
    }

    public synchronized void response(String sender, NavigationExchange incoming, long now) {
        mergeExchange(incoming, now);
        emit("NAVIGATION_GOSSIP_RECEIVED", "peerNodeId=" + sender);
    }

    private void mergeExchange(NavigationExchange incoming, long now) {
        NavigationPeerDescriptor sender = new NavigationPeerDescriptor(
                incoming.senderDescriptor().nodeId(), incoming.senderDescriptor().host(), incoming.senderDescriptor().port(),
                incoming.senderSubscriptions(), now);
        // The sender itself is always fresh (we just heard from it); propagated candidates are not.
        mergeCandidate(sender, now);
        incoming.candidates().forEach(candidate -> mergeCandidate(candidate, now));
        emit("NAVIGATION_VIEW_UPDATED", "targets=" + view.snapshot().size());
    }

    private void mergeCandidate(NavigationPeerDescriptor candidate, long now) {
        if (candidate.nodeId().equals(nodeId)) {
            return;
        }
        // Never resurrect a candidate that is already stale: otherwise a departed peer could be
        // propagated back into a view forever by nodes that have not pruned it yet themselves.
        if (now - candidate.freshness() > staleAfterMs) {
            return;
        }
        for (int target : fingerTopics) {
            view.offer(target, candidate, ordering, nodeId);
        }
    }

    private List<NavigationPeerDescriptor> selectCandidatesFor(NavigationPeerDescriptor partner) {
        Set<Integer> partnerSubscribedOrdinals = new LinkedHashSet<>();
        for (String topicId : partner.subscribedTopicIds()) {
            ordering.ordinal(topicId).ifPresent(partnerSubscribedOrdinals::add);
        }
        if (partnerSubscribedOrdinals.isEmpty()) {
            // Partner subscriptions are not yet known: fall back to our whole view so it can be learned.
            return view.flatten().stream().filter(p -> !p.nodeId().equals(partner.nodeId())).toList();
        }
        Set<Integer> partnerFingerTopics = FingerTopics.forSubscriptions(partnerSubscribedOrdinals, ordering.size(), base);
        Map<String, NavigationPeerDescriptor> selected = new LinkedHashMap<>();
        for (int target : partnerFingerTopics) {
            for (NavigationPeerDescriptor candidate : view.candidatesFor(target)) {
                if (!candidate.nodeId().equals(partner.nodeId())) {
                    selected.putIfAbsent(candidate.nodeId(), candidate);
                }
            }
        }
        return List.copyOf(selected.values());
    }

    private NavigationPeerDescriptor selfDescriptor(long now) {
        return new NavigationPeerDescriptor(nodeId, host, port, List.copyOf(subscriptions), now);
    }

    private void recomputeFingerTopics() {
        Set<Integer> subscribedOrdinals = new LinkedHashSet<>();
        for (String topicId : subscriptions) {
            ordering.ordinal(topicId).ifPresent(subscribedOrdinals::add);
        }
        Set<Integer> newFingerTopics = FingerTopics.forSubscriptions(subscribedOrdinals, ordering.size(), base);
        fingerTopics = newFingerTopics;
        view.retainTargets(newFingerTopics);
        emit("NAVIGATION_FINGER_TOPICS_UPDATED", "fingerTopics=" + newFingerTopics);
    }

    private void emit(String event, String fields) {
        log.accept(event + " nodeId=" + nodeId + " viewSize=" + view.flatten().size() + " cycle=" + cycle + " " + fields);
    }
}
