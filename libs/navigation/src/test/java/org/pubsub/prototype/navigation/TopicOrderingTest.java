package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicOrderingTest {

    @Test
    void assignsOrdinalsFromLexicographicOrder() {
        TopicOrdering ordering = TopicOrdering.of(Set.of("c".repeat(64), "a".repeat(64), "b".repeat(64)));

        assertEquals(List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)), ordering.topicIds());
        assertEquals(0, ordering.ordinal("a".repeat(64)).getAsInt());
        assertEquals(1, ordering.ordinal("b".repeat(64)).getAsInt());
        assertEquals(2, ordering.ordinal("c".repeat(64)).getAsInt());
        assertEquals(3, ordering.size());
    }

    @Test
    void unknownTopicHasNoOrdinal() {
        TopicOrdering ordering = TopicOrdering.of(Set.of("a".repeat(64)));

        assertTrue(ordering.ordinal("z".repeat(64)).isEmpty());
        assertTrue(ordering.topicIdAt(5).isEmpty());
    }

    @Test
    void recomputesWhenTopicsAreAddedOrDeleted() {
        TopicOrdering before = TopicOrdering.of(Set.of("a".repeat(64), "b".repeat(64)));
        TopicOrdering added = TopicOrdering.of(Set.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
        TopicOrdering deleted = TopicOrdering.of(Set.of("a".repeat(64)));

        assertEquals(2, before.size());
        assertEquals(3, added.size());
        assertEquals(0, added.ordinal("a".repeat(64)).getAsInt());
        assertEquals(2, added.ordinal("c".repeat(64)).getAsInt());
        assertEquals(1, deleted.size());
        assertTrue(deleted.ordinal("b".repeat(64)).isEmpty());
        assertTrue(!before.equals(added));
    }

    @Test
    void deduplicatesTopicIds() {
        TopicOrdering ordering = TopicOrdering.of(List.of("a".repeat(64), "a".repeat(64), "b".repeat(64)));

        assertEquals(2, ordering.size());
    }
}
