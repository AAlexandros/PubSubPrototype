package org.pubsub.prototype.persistence.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NodeEventStoreTest {
    private static final String TOPIC = "11".repeat(32);
    @TempDir Path temp;

    @Test
    void persistsFullEnvelopeAndRebuildsIndexesWithBoundedOrderedQueries() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope first = event(keys, 0, "first");
        EventEnvelope second = event(keys, 1, "second");
        String publisher = EventCrypto.publisherKeyId(first);
        NodeEventStore store = new NodeEventStore(temp);
        assertTrue(store.store(second));
        assertTrue(store.store(first));
        assertFalse(store.store(first));
        assertEquals(2, store.size());
        assertEquals(first, store.getById(first.eventId()).orElseThrow());
        assertEquals(second, store.get(TOPIC, publisher, 1).orElseThrow());
        assertEquals(first, store.list(TOPIC, publisher, 0, 1).events().getFirst());
        assertTrue(store.list(TOPIC, publisher, 0, 1).hasMore());
        assertEquals(second, store.list(TOPIC, publisher, 1, 1).events().getFirst());
        assertThrows(IllegalArgumentException.class, () -> store.list(null, null, 0, 501));
        NodeEventStore reloaded = new NodeEventStore(temp);
        assertEquals(first, reloaded.getById(first.eventId()).orElseThrow());
        assertEquals(second.payload(), reloaded.get(TOPIC, publisher, 1).orElseThrow().payload());
        assertEquals(2, reloaded.size());
    }

    @Test
    void rejectsConflictsAndCorruptFilesWithoutAdvancingState() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope first = event(keys, 0, "first");
        EventEnvelope conflict = event(keys, 0, "other");
        NodeEventStore store = new NodeEventStore(temp);
        store.store(first);
        assertThrows(IllegalArgumentException.class, () -> store.store(conflict));
        assertEquals(first, store.getById(first.eventId()).orElseThrow());
        Path path = temp.resolve(TOPIC).resolve(EventCrypto.publisherKeyId(first)).resolve("0.json");
        Files.writeString(path, "{partial");
        assertThrows(IllegalStateException.class, () -> new NodeEventStore(temp));
    }

    private static EventEnvelope event(KeyPair keys, long sequence, String payload) {
        EventEnvelope unsigned = EventEnvelope.unsigned(TOPIC,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), sequence, 100 + sequence,
                Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keys.getPrivate()),
                EventCrypto.eventId(unsigned)).withProgressSignature(EventCrypto.signProgress(unsigned, keys.getPrivate()));
    }
}
