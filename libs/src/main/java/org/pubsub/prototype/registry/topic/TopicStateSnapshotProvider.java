package org.pubsub.prototype.registry.topic;

import java.util.List;

/** Supplies a snapshot of the currently active topics for maintenance and reconciliation. */
@FunctionalInterface
public interface TopicStateSnapshotProvider {
    /** Returns an immutable snapshot containing active topic states. */
    List<TopicState> activeTopics();
}
