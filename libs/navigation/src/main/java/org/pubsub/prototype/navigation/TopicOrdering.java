package org.pubsub.prototype.navigation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Deterministic ordinal assignment: active topic ids sorted lexicographically, ordinals 0..T-1. */
public record TopicOrdering(List<String> topicIds) {
    public TopicOrdering {
        topicIds = topicIds.stream().distinct().sorted().toList();
    }

    public static TopicOrdering of(Collection<String> activeTopicIds) {
        return new TopicOrdering(List.copyOf(activeTopicIds));
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
