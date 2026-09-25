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
    // The core structure that holds finger-ordinals and maps them to nearby peers
    private final Map<Integer, List<NavigationPeerDescriptor>> buckets = new LinkedHashMap<>();

    NavigationView(int capacity) {
        this.capacity = capacity;
    }

    synchronized boolean offer(int target, NavigationPeerDescriptor candidate, TopicIndex topicIndex, String selfNodeId) {
        if (candidate.nodeId().equals(selfNodeId)) {
            return false;
        }
        // Get the peers for the specific topic ordinal, or map it to an empty list if not present
        List<NavigationPeerDescriptor> bucket = buckets.computeIfAbsent(target, ignored -> new ArrayList<>());
        // If a descriptor already exists for the specific node, keep the freshest descriptor
        int existingIndex = indexOf(bucket, candidate.nodeId());
        if (existingIndex >= 0) {
            NavigationPeerDescriptor existing = bucket.get(existingIndex);
            if (candidate.freshness() > existing.freshness()) {
                bucket.set(existingIndex, candidate);
                return true;
            }
            return false;
        }
        // Compute the distance only when this is a different node competing for the bucket
        int newDistance = distanceOf(candidate, target, topicIndex);
        if (bucket.size() < capacity) {
            bucket.add(candidate);
            return true;
        }
        int worstIndex = worstIndex(bucket, target, topicIndex);
        NavigationPeerDescriptor worst = bucket.get(worstIndex);
        int worstDistance = distanceOf(worst, target, topicIndex);
        // A closer candidate wins, at equal distance, prefer the newly acquired (fresh) link
        if (newDistance <= worstDistance) {
            bucket.set(worstIndex, candidate);
            return true;
        }
        return false;
    }

    // Get the peers that were stored for the specific target ordinal
    synchronized List<NavigationPeerDescriptor> peersForTarget(int target) {
        return List.copyOf(buckets.getOrDefault(target, List.of()));
    }

    // Returns a deep copy of the current ordinal-to-peers mapping
    // Making both the inner list and outer map, and having immutalbe descriptors, makes the whole structure immutable
    synchronized Map<Integer, List<NavigationPeerDescriptor>> snapshot() {
        // We make the inner list immutable, in terms of modification operations
        Map<Integer, List<NavigationPeerDescriptor>> copy = new LinkedHashMap<>();
        buckets.forEach((target, bucket) -> copy.put(target, List.copyOf(bucket)));
        // We make the map immutable, in terms of modification operations
        return Map.copyOf(copy);
    }

    // Flatten the whole descriptor set, from the <target, List<Descriptor>> map
    synchronized List<NavigationPeerDescriptor> flatten() {
        Map<String, NavigationPeerDescriptor> byNodeId = new LinkedHashMap<>();
        buckets.values().forEach(bucket -> bucket.forEach(peer ->
                byNodeId.merge(peer.nodeId(), peer, (a, b) -> a.freshness() >= b.freshness() ? a : b)));
        return List.copyOf(byNodeId.values());
    }

    // From the set of all targets, retain only a specific id set
    synchronized void retainTargets(Set<Integer> keep) {
        buckets.keySet().retainAll(keep);
    }

    // Clear the contents of all buckets
    synchronized void clear() {
        buckets.clear();
    }

    /** Removes entries older than maxAgeMs, and returns the removed node ids for logging. */
    synchronized List<String> pruneStale(long now, long maxAgeMs) {
        // Keep a list of removed descriptors, for logging purposes
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

    // Remove the node descriptors for a specific node id from all buckets
    synchronized void removePeer(String nodeId) {
        buckets.values().forEach(bucket -> bucket.removeIf(peer -> peer.nodeId().equals(nodeId)));
    }

    // Find the index of a peer with the given node id within a bucket, or -1 if not found
    private static int indexOf(List<NavigationPeerDescriptor> bucket, String nodeId) {
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).nodeId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    // Return the index of the worst descriptor in a bucket, based on distance to the target
    private static int worstIndex(List<NavigationPeerDescriptor> bucket, int target, TopicIndex topicIndex) {
        int worstIndex = 0;
        int worstDistance = -1;
        for (int i = 0; i < bucket.size(); i++) {
            NavigationPeerDescriptor candidate = bucket.get(i);
            int distance = distanceOf(candidate, target, topicIndex);
            if (distance > worstDistance) {
                worstDistance = distance;
                worstIndex = i;
            }
        }
        return worstIndex;
    }

    // Returns how close the candidate peer is to the target topic.
    // This will determine if the descriptor will be kept or discarded
    private static int distanceOf(NavigationPeerDescriptor candidate, int target, TopicIndex topicIndex) {
        int best = Integer.MAX_VALUE;
        // For each topic of the candidate peer, calculate the distance to the target and keep the best (smallest) distance
        for (String topicId : candidate.subscribedTopicIds()) {
            var ordinal = topicIndex.ordinal(topicId);
            if (ordinal.isPresent()) {
                best = Math.min(best, FingerTopics.distance(ordinal.getAsInt(), target, topicIndex.size()));
            }
        }
        return best;
    }
}
