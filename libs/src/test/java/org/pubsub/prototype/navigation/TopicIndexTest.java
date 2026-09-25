package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicIndexTest {

    @Test
    void assignsOrdinalsFromLexicographicOrder() {
        TopicIndex topicIndex = TopicIndex.of(Set.of("c".repeat(64), "a".repeat(64), "b".repeat(64)));

        assertEquals(List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)), topicIndex.topicIds());
        assertEquals(0, topicIndex.ordinal("a".repeat(64)).getAsInt());
        assertEquals(1, topicIndex.ordinal("b".repeat(64)).getAsInt());
        assertEquals(2, topicIndex.ordinal("c".repeat(64)).getAsInt());
        assertEquals(3, topicIndex.size());
    }

    @Test
    void unknownTopicHasNoOrdinal() {
        TopicIndex topicIndex = TopicIndex.of(Set.of("a".repeat(64)));

        assertTrue(topicIndex.ordinal("z".repeat(64)).isEmpty());
        assertTrue(topicIndex.topicIdAt(5).isEmpty());
    }

    @Test
    void recomputesWhenTopicsAreAddedOrDeleted() {
        TopicIndex before = TopicIndex.of(Set.of("a".repeat(64), "b".repeat(64)));
        TopicIndex added = TopicIndex.of(Set.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
        TopicIndex deleted = TopicIndex.of(Set.of("a".repeat(64)));

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
        TopicIndex topicIndex = TopicIndex.of(List.of("a".repeat(64), "a".repeat(64), "b".repeat(64)));

        assertEquals(2, topicIndex.size());
    }
}
