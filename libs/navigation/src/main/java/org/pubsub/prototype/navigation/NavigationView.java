package org.pubsub.prototype.navigation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded per-target-topic candidate view. Never retains self or duplicate node ids within a target slot. */
final class NavigationView {
    private final int capacity;
    private final Map<Integer, List<NavigationPeerDescriptor>> buckets = new LinkedHashMap<>();

    NavigationView(int capacity) {
        this.capacity = capacity;
    }

    synchronized boolean offer(int target, NavigationPeerDescriptor candidate, TopicOrdering ordering, String selfNodeId) {
        if (candidate.nodeId().equals(selfNodeId)) {
            return false;
        }
        List<NavigationPeerDescriptor> bucket = buckets.computeIfAbsent(target, ignored -> new ArrayList<>());
        int newDistance = distanceOf(candidate, target, ordering);
        int existingIndex = indexOf(bucket, candidate.nodeId());
        if (existingIndex >= 0) {
            NavigationPeerDescriptor existing = bucket.get(existingIndex);
            int existingDistance = distanceOf(existing, target, ordering);
            if (betterOrFresher(newDistance, candidate.freshness(), existingDistance, existing.freshness())) {
                bucket.set(existingIndex, candidate);
                return true;
            }
            return false;
        }
        if (bucket.size() < capacity) {
            bucket.add(candidate);
            return true;
        }
        int worstIndex = worstIndex(bucket, target, ordering);
        NavigationPeerDescriptor worst = bucket.get(worstIndex);
        int worstDistance = distanceOf(worst, target, ordering);
        if (betterOrFresher(newDistance, candidate.freshness(), worstDistance, worst.freshness())) {
            bucket.set(worstIndex, candidate);
            return true;
        }
        return false;
    }

    synchronized List<NavigationPeerDescriptor> candidatesFor(int target) {
        return List.copyOf(buckets.getOrDefault(target, List.of()));
    }

    synchronized Map<Integer, List<NavigationPeerDescriptor>> snapshot() {
        Map<Integer, List<NavigationPeerDescriptor>> copy = new LinkedHashMap<>();
        buckets.forEach((target, bucket) -> copy.put(target, List.copyOf(bucket)));
        return Map.copyOf(copy);
    }

    synchronized List<NavigationPeerDescriptor> flatten() {
        Map<String, NavigationPeerDescriptor> byNodeId = new LinkedHashMap<>();
        buckets.values().forEach(bucket -> bucket.forEach(peer ->
                byNodeId.merge(peer.nodeId(), peer, (a, b) -> a.freshness() >= b.freshness() ? a : b)));
        return List.copyOf(byNodeId.values());
    }

    synchronized void retainTargets(Set<Integer> keep) {
        buckets.keySet().retainAll(keep);
    }

    synchronized void clear() {
        buckets.clear();
    }

    /** Removes entries older than maxAgeMs; returns the removed node ids for logging. */
    synchronized List<String> pruneStale(long now, long maxAgeMs) {
        List<String> removed = new ArrayList<>();
        for (List<NavigationPeerDescriptor> bucket : buckets.values()) {
            Iterator<NavigationPeerDescriptor> iterator = bucket.iterator();
            while (iterator.hasNext()) {
                NavigationPeerDescriptor candidate = iterator.next();
                if (now - candidate.freshness() > maxAgeMs) {
                    removed.add(candidate.nodeId());
                    iterator.remove();
                }
            }
        }
        return removed;
    }

    synchronized void removePeer(String nodeId) {
        buckets.values().forEach(bucket -> bucket.removeIf(peer -> peer.nodeId().equals(nodeId)));
    }

    private static boolean betterOrFresher(int newDistance, long newFreshness, int oldDistance, long oldFreshness) {
        if (newDistance != oldDistance) {
            return newDistance < oldDistance;
        }
        return newFreshness > oldFreshness;
    }

    private static int indexOf(List<NavigationPeerDescriptor> bucket, String nodeId) {
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).nodeId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private static int worstIndex(List<NavigationPeerDescriptor> bucket, int target, TopicOrdering ordering) {
        int worstIndex = 0;
        int worstDistance = -1;
        long worstFreshness = Long.MAX_VALUE;
        for (int i = 0; i < bucket.size(); i++) {
            NavigationPeerDescriptor candidate = bucket.get(i);
            int distance = distanceOf(candidate, target, ordering);
            if (distance > worstDistance || (distance == worstDistance && candidate.freshness() < worstFreshness)) {
                worstDistance = distance;
                worstFreshness = candidate.freshness();
                worstIndex = i;
            }
        }
        return worstIndex;
    }

    private static int distanceOf(NavigationPeerDescriptor candidate, int target, TopicOrdering ordering) {
        int best = Integer.MAX_VALUE;
        for (String topicId : candidate.subscribedTopicIds()) {
            var ordinal = ordering.ordinal(topicId);
            if (ordinal.isPresent()) {
                best = Math.min(best, FingerTopics.distance(ordinal.getAsInt(), target, ordering.size()));
            }
        }
        return best;
    }
}
