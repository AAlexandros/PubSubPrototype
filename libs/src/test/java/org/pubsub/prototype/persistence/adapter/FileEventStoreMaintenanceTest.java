package org.pubsub.prototype.persistence.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEventStoreMaintenanceTest {
    private static final String TOPIC = "10".repeat(32);
    private static final String SERVER = "30".repeat(32);

    @TempDir Path temp;

    @Test
    void topicProgressMergesMonotonicallyAndCanBeReleased() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        assertTrue(store.updateProgress(TOPIC, progress(publisher, 4, 100)));
        assertFalse(store.updateProgress(TOPIC, progress(publisher, 3, 200)));
        assertFalse(store.updateProgress(TOPIC, progress(publisher, 4, 99)));
        assertTrue(store.updateProgress(TOPIC, progress(publisher, 4, 101)));
        assertEquals(101, store.publisherProgress(TOPIC).getFirst().latestTimestamp());
        assertEquals(java.util.List.of(TOPIC), store.topicIds());
        assertTrue(store.deleteTopicLog(TOPIC));
        assertTrue(store.topicIds().isEmpty());
    }

    private static PublisherProgress progress(KeyPair publisher, long sequence, long timestamp) {
        EventEnvelope unsigned = EventEnvelope.unsigned(TOPIC,
                Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded()), sequence, timestamp, "");
        return PublisherProgress.fromEvent(unsigned.withProgressSignature(
                EventCrypto.signProgress(unsigned, publisher.getPrivate())));
    }

    @Test
    void persistentServerIdentityRejectsAReconfiguredVolume() {
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        store.ensureServerIdentity(SERVER);
        store.ensureServerIdentity(SERVER);
        assertThrows(IllegalStateException.class, () -> store.ensureServerIdentity("40".repeat(32)));
    }
}
