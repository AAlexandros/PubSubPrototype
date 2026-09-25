package org.pubsub.prototype.navigation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Maps active topic IDs to their positions in a deduplicated, lexicographically
 * sorted list. Nodes with the same topic set therefore build the same index.
 * <p>
 * Nodes may temporarily have different indexes while registry updates propagate.
 * This can reduce routing quality, but exchanges carry topic IDs rather than
 * positions. When the index changes, {@link NavigationLayer} clears and rebuilds
 * its position-dependent state.
 * </p>
 */
public record TopicIndex(List<String> topicIds) {
    public TopicIndex {
        topicIds = topicIds.stream().distinct().sorted().toList();
    }

    public static TopicIndex of(Collection<String> activeTopicIds) {
        return new TopicIndex(List.copyOf(activeTopicIds));
    }

    public int size() {
        return topicIds.size();
    }

    public OptionalInt ordinal(String topicId) {
        int index = topicIds.indexOf(topicId);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    public Optional<String> topicIdAt(int ordinal) {
        return ordinal >= 0 && ordinal < topicIds.size() ? Optional.of(topicIds.get(ordinal)) : Optional.empty();
    }
}
