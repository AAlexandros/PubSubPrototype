package org.pubsub.prototype.persistence.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.persistence.PublisherProgress;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEventStoreMaintenanceTest {
    private static final String TOPIC = "10".repeat(32);
    private static final String PUBLISHER = "20".repeat(32);
    private static final String SERVER = "30".repeat(32);

    @TempDir Path temp;

    @Test
    void topicProgressMergesMonotonicallyAndCanBeReleased() {
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        assertTrue(store.updateProgress(TOPIC, new PublisherProgress(PUBLISHER, 4, 100)));
        assertFalse(store.updateProgress(TOPIC, new PublisherProgress(PUBLISHER, 3, 200)));
        assertFalse(store.updateProgress(TOPIC, new PublisherProgress(PUBLISHER, 4, 99)));
        assertTrue(store.updateProgress(TOPIC, new PublisherProgress(PUBLISHER, 4, 101)));
        assertEquals(101, store.publisherProgress(TOPIC).getFirst().latestTimestamp());
        assertEquals(java.util.List.of(TOPIC), store.topicIds());
        assertTrue(store.deleteTopicLog(TOPIC));
        assertTrue(store.topicIds().isEmpty());
    }

    @Test
    void persistentServerIdentityRejectsAReconfiguredVolume() {
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        store.ensureServerIdentity(SERVER);
        store.ensureServerIdentity(SERVER);
        assertThrows(IllegalStateException.class, () -> store.ensureServerIdentity("40".repeat(32)));
    }
}
