package org.pubsub.prototype.dissemination;

import org.pubsub.prototype.navigation.NavigationPeerDescriptor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.function.Function;

/** Transport-independent per-topic ring and random-link state machine. */
public final class DisseminationEngine {
    public static final int PROTOCOL_VERSION = 1;
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);

    public record Outgoing(DisseminationPeerDescriptor peer, UUID requestId, DisseminationExchange exchange) {
    }

    private static final class TopicState {
        final String topicId;
        final Map<String, DisseminationPeerDescriptor> candidates = new LinkedHashMap<>();
        DisseminationPeerDescriptor predecessor;
        DisseminationPeerDescriptor successor;
        List<DisseminationPeerDescriptor> randomPeers = List.of();

        TopicState(String topicId) {
            this.topicId = topicId;
        }
    }

    private final String nodeId;
    private final String host;
    private final int port;
    private final int randomLinkCount;
    private final long staleAfterMs;
    private final Random random;
    private final Consumer<String> log;
    private final Map<String, TopicState> topics = new LinkedHashMap<>();
    private final Map<String, Long> unavailablePeers = new LinkedHashMap<>();
    private long cycle;

    public DisseminationEngine(String nodeId, String host, int port, int randomLinkCount,
                               long staleAfterMs, Random random, Consumer<String> log) {
        if (nodeId == null || !nodeId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid local nodeId");
        }
        if (randomLinkCount < 1 || randomLinkCount > 256 || staleAfterMs < 1) {
            throw new IllegalArgumentException("Invalid dissemination configuration");
        }
        this.nodeId = nodeId;
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.randomLinkCount = randomLinkCount;
        this.staleAfterMs = staleAfterMs;
        this.random = Objects.requireNonNull(random, "random");
        this.log = Objects.requireNonNull(log, "log");
    }

    public synchronized void subscribe(String topicId, Collection<NavigationPeerDescriptor> navigation, long now) {
        validateTopicId(topicId);
        boolean added = !topics.containsKey(topicId);
        TopicState state = topics.computeIfAbsent(topicId, TopicState::new);
        mergeNavigation(state, navigation, now);
        recompute(state, navigation);
        if (added) {
            emit("DISSEMINATION_STARTED", state, "");
        }
    }

    public synchronized void unsubscribe(String topicId) {
        topics.remove(topicId);
    }

    public synchronized void retainActiveTopics(Set<String> activeTopicIds) {
        topics.keySet().removeIf(topicId -> !activeTopicIds.contains(topicId));
    }

    public synchronized Set<String> subscriptions() {
        return Set.copyOf(topics.keySet());
    }

    public synchronized Optional<TopicDisseminationView> view(String topicId) {
        TopicState state = topics.get(topicId);
        return state == null ? Optional.empty() : Optional.of(snapshot(state));
    }

    public synchronized Map<String, TopicDisseminationView> views() {
        Map<String, TopicDisseminationView> result = new LinkedHashMap<>();
        topics.forEach((topicId, state) -> result.put(topicId, snapshot(state)));
        return Map.copyOf(result);
    }

    /** Refreshes from Navigation, repairs stale links, and creates at most one gossip request per topic. */
    public synchronized List<Outgoing> cycle(long now,
                                              Function<String, List<NavigationPeerDescriptor>> navigationProvider) {
        cycle++;
        List<Outgoing> outgoing = new ArrayList<>();
        for (TopicState state : topics.values()) {
            List<NavigationPeerDescriptor> navigation = navigationProvider.apply(state.topicId);
            pruneStale(state, now);
            mergeNavigation(state, navigation, now);
            recompute(state, navigation);
            emit("DISSEMINATION_CYCLE", state, "");
            choosePartner(state).ifPresent(partner -> {
                UUID requestId = new UUID(random.nextLong(), random.nextLong());
                List<DisseminationPeerDescriptor> selected = selectClosestTo(state, partner.nodeId());
                DisseminationExchange exchange = new DisseminationExchange(
                        state.topicId, selfDescriptor(state.topicId, now), selected, PROTOCOL_VERSION);
                outgoing.add(new Outgoing(partner, requestId, exchange));
                emit("DISSEMINATION_GOSSIP_SENT", state,
                        "peerNodeId=" + partner.nodeId() + " candidates=" + selected.size());
            });
        }
        return List.copyOf(outgoing);
    }

    public synchronized DisseminationExchange request(String senderNodeId, DisseminationExchange incoming,
                                                       Collection<NavigationPeerDescriptor> navigation, long now) {
        TopicState state = requireExchange(senderNodeId, incoming);
        mergeIncoming(state, incoming, now);
        mergeNavigation(state, navigation, now);
        recompute(state, navigation);
        emit("DISSEMINATION_GOSSIP_RECEIVED", state, "peerNodeId=" + senderNodeId);
        return new DisseminationExchange(state.topicId, selfDescriptor(state.topicId, now),
                selectClosestTo(state, senderNodeId), PROTOCOL_VERSION);
    }

    public synchronized void response(String senderNodeId, DisseminationExchange incoming,
                                      Collection<NavigationPeerDescriptor> navigation, long now) {
        TopicState state = requireExchange(senderNodeId, incoming);
        mergeIncoming(state, incoming, now);
        mergeNavigation(state, navigation, now);
        recompute(state, navigation);
        emit("DISSEMINATION_GOSSIP_RECEIVED", state, "peerNodeId=" + senderNodeId);
    }

    public synchronized void peerUnavailable(String peerNodeId) {
        unavailablePeers.put(peerNodeId, System.currentTimeMillis());
        for (TopicState state : topics.values()) {
            if (state.candidates.remove(peerNodeId) != null) {
                emit("DISSEMINATION_PEER_REMOVED", state, "peerNodeId=" + peerNodeId);
                recompute(state, List.of());
            }
        }
    }

    /** Computes forwarding targets based on whether the source is a ring or random link. */
    public synchronized List<DisseminationPeerDescriptor> forwardingTargets(String topicId, String sourceNodeId) {
        TopicState state = topics.get(topicId);
        if (state == null) {
            return List.of();
        }
        LinkedHashMap<String, DisseminationPeerDescriptor> targets = new LinkedHashMap<>();
        if (sourceNodeId == null) {
            add(targets, state.predecessor, null);
            add(targets, state.successor, null);
            state.randomPeers.forEach(peer -> add(targets, peer, null));
        } else {
            boolean predecessorSource = samePeer(state.predecessor, sourceNodeId);
            boolean successorSource = samePeer(state.successor, sourceNodeId);
            if (predecessorSource || successorSource) {
                if (predecessorSource) add(targets, state.successor, sourceNodeId);
                if (successorSource) add(targets, state.predecessor, sourceNodeId);
                state.randomPeers.forEach(peer -> add(targets, peer, sourceNodeId));
            } else {
                add(targets, state.predecessor, sourceNodeId);
                add(targets, state.successor, sourceNodeId);
            }
        }
        return List.copyOf(targets.values());
    }

    private TopicState requireExchange(String senderNodeId, DisseminationExchange incoming) {
        if (incoming.protocolVersion() != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported dissemination protocol version");
        }
        if (!incoming.senderDescriptor().nodeId().equals(senderNodeId)) {
            throw new IllegalArgumentException("Dissemination sender identity mismatch");
        }
        TopicState state = topics.get(incoming.topicId());
        if (state == null) {
            throw new IllegalArgumentException("Not subscribed to dissemination topic");
        }
        return state;
    }

    private void mergeIncoming(TopicState state, DisseminationExchange incoming, long now) {
        DisseminationPeerDescriptor sender = incoming.senderDescriptor();
        // A direct authenticated transport exchange is proof that a restarted or reconnected peer is live.
        unavailablePeers.remove(sender.nodeId());
        merge(state, new DisseminationPeerDescriptor(sender.nodeId(), sender.host(), sender.port(),
                sender.subscribedTopicIds(), now), now);
        incoming.candidates().forEach(candidate -> {
            Long unavailableSince = unavailablePeers.get(candidate.nodeId());
            // A candidate freshly observed by another dissemination peer after our disconnect
            // is transitive evidence of a restarted identity; pre-failure descriptors stay blocked.
            if (unavailableSince != null && candidate.freshness() > unavailableSince) {
                unavailablePeers.remove(candidate.nodeId());
            }
            merge(state, candidate, now);
        });
    }

    private void mergeNavigation(TopicState state, Collection<NavigationPeerDescriptor> navigation, long now) {
        navigation.stream()
                .filter(peer -> peer.subscribedTopicIds().contains(state.topicId))
                .filter(peer -> !unavailablePeers.containsKey(peer.nodeId()))
                .map(DisseminationPeerDescriptor::fromNavigation)
                .forEach(peer -> merge(state, peer, now));
    }

    private void merge(TopicState state, DisseminationPeerDescriptor peer, long now) {
        if (peer.nodeId().equals(nodeId) || unavailablePeers.containsKey(peer.nodeId())
                || !peer.subscribedTopicIds().contains(state.topicId)
                || now - peer.freshness() > staleAfterMs) {
            return;
        }
        state.candidates.merge(peer.nodeId(), peer,
                (oldPeer, newPeer) -> newPeer.freshness() >= oldPeer.freshness() ? newPeer : oldPeer);
    }

    private void pruneStale(TopicState state, long now) {
        List<String> removed = state.candidates.values().stream()
                .filter(peer -> now - peer.freshness() > staleAfterMs)
                .map(DisseminationPeerDescriptor::nodeId)
                .toList();
        removed.forEach(state.candidates::remove);
        removed.forEach(peer -> emit("DISSEMINATION_PEER_REMOVED", state, "peerNodeId=" + peer));
    }

    private void recompute(TopicState state, Collection<NavigationPeerDescriptor> navigation) {
        DisseminationPeerDescriptor oldPredecessor = state.predecessor;
        DisseminationPeerDescriptor oldSuccessor = state.successor;
        List<DisseminationPeerDescriptor> oldRandom = state.randomPeers;
        List<DisseminationPeerDescriptor> peers = List.copyOf(state.candidates.values());
        state.predecessor = predecessor(nodeId, peers);
        state.successor = successor(nodeId, peers);

        List<DisseminationPeerDescriptor> randomPool = navigation.stream()
                .filter(peer -> peer.subscribedTopicIds().contains(state.topicId) && !peer.nodeId().equals(nodeId))
                .filter(peer -> !unavailablePeers.containsKey(peer.nodeId()))
                .map(DisseminationPeerDescriptor::fromNavigation)
                .sorted(Comparator.comparing(DisseminationPeerDescriptor::nodeId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.Collections.shuffle(randomPool, random);
        state.randomPeers = List.copyOf(randomPool.subList(0, Math.min(randomLinkCount, randomPool.size())));

        if (!samePeer(oldPredecessor, state.predecessor)) {
            emit("DISSEMINATION_PREDECESSOR_CHANGED", state, "peerNodeId=" + idOf(state.predecessor) + " role=predecessor");
        }
        if (!samePeer(oldSuccessor, state.successor)) {
            emit("DISSEMINATION_SUCCESSOR_CHANGED", state, "peerNodeId=" + idOf(state.successor) + " role=successor");
        }
        if (!ids(oldRandom).equals(ids(state.randomPeers))) {
            emit("DISSEMINATION_RANDOM_PEER_CHANGED", state, "peerNodeId=" + ids(state.randomPeers) + " role=random");
        }
        if (!samePeer(oldPredecessor, state.predecessor) || !samePeer(oldSuccessor, state.successor)
                || !ids(oldRandom).equals(ids(state.randomPeers))) {
            emit("DISSEMINATION_VIEW_UPDATED", state, "");
        }
    }

    private Optional<DisseminationPeerDescriptor> choosePartner(TopicState state) {
        Map<String, DisseminationPeerDescriptor> neighbors = new LinkedHashMap<>();
        add(neighbors, state.predecessor, null);
        add(neighbors, state.successor, null);
        state.randomPeers.forEach(peer -> add(neighbors, peer, null));
        return neighbors.values().stream().min(Comparator
                .comparing((DisseminationPeerDescriptor peer) -> circularDistance(nodeId, peer.nodeId()))
                .thenComparing(DisseminationPeerDescriptor::nodeId));
    }

    private List<DisseminationPeerDescriptor> selectClosestTo(TopicState state, String targetNodeId) {
        List<DisseminationPeerDescriptor> candidates = new ArrayList<>(state.candidates.values());
        candidates.removeIf(peer -> peer.nodeId().equals(targetNodeId));
        LinkedHashMap<String, DisseminationPeerDescriptor> selected = new LinkedHashMap<>();
        add(selected, predecessor(targetNodeId, candidates), null);
        add(selected, successor(targetNodeId, candidates), null);
        return List.copyOf(selected.values());
    }

    static DisseminationPeerDescriptor predecessor(String local, Collection<DisseminationPeerDescriptor> peers) {
        return peers.stream().filter(peer -> !peer.nodeId().equals(local))
                .filter(peer -> peer.nodeId().compareTo(local) < 0)
                .max(Comparator.comparing(DisseminationPeerDescriptor::nodeId))
                .orElseGet(() -> peers.stream().filter(peer -> !peer.nodeId().equals(local))
                        .max(Comparator.comparing(DisseminationPeerDescriptor::nodeId)).orElse(null));
    }

    static DisseminationPeerDescriptor successor(String local, Collection<DisseminationPeerDescriptor> peers) {
        return peers.stream().filter(peer -> !peer.nodeId().equals(local))
                .filter(peer -> peer.nodeId().compareTo(local) > 0)
                .min(Comparator.comparing(DisseminationPeerDescriptor::nodeId))
                .orElseGet(() -> peers.stream().filter(peer -> !peer.nodeId().equals(local))
                        .min(Comparator.comparing(DisseminationPeerDescriptor::nodeId)).orElse(null));
    }

    private DisseminationPeerDescriptor selfDescriptor(String topicId, long now) {
        return new DisseminationPeerDescriptor(nodeId, host, port, List.of(topicId), now);
    }

    private static BigInteger circularDistance(String a, String b) {
        BigInteger direct = new BigInteger(a, 16).subtract(new BigInteger(b, 16)).abs();
        return direct.min(MODULUS.subtract(direct));
    }

    private static TopicDisseminationView snapshot(TopicState state) {
        return new TopicDisseminationView(state.topicId, state.predecessor, state.successor, state.randomPeers);
    }

    private static void add(Map<String, DisseminationPeerDescriptor> target,
                            DisseminationPeerDescriptor peer, String excluded) {
        if (peer != null && !peer.nodeId().equals(excluded)) target.putIfAbsent(peer.nodeId(), peer);
    }

    private static boolean samePeer(DisseminationPeerDescriptor peer, String nodeId) {
        return peer != null && peer.nodeId().equals(nodeId);
    }

    private static boolean samePeer(DisseminationPeerDescriptor a, DisseminationPeerDescriptor b) {
        return Objects.equals(idOf(a), idOf(b));
    }

    private static String idOf(DisseminationPeerDescriptor peer) {
        return peer == null ? "null" : peer.nodeId();
    }

    private static Set<String> ids(List<DisseminationPeerDescriptor> peers) {
        return peers.stream().map(DisseminationPeerDescriptor::nodeId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void emit(String event, TopicState state, String fields) {
        log.accept(event + " topicId=" + state.topicId + " nodeId=" + nodeId + " cycle=" + cycle
                + (fields.isBlank() ? "" : " " + fields));
    }

    private static void validateTopicId(String topicId) {
        if (topicId == null || !topicId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid topicId");
        }
    }
}
