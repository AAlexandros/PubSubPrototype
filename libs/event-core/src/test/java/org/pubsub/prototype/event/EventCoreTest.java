package org.pubsub.prototype.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCoreTest {
    private static final TopicId TOPIC_ID = new TopicId("a".repeat(64));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void canonicalEncodingSigningAndEventIdAreDeterministic() throws Exception {
        KeyPair keyPair = keyPair();
        EventPublisher publisher = new EventPublisher(keyPair, CLOCK, tempDir);

        EventEnvelope first = publisher.publish(TOPIC_ID, "hello".getBytes());
        EventEnvelope sameBody = EventEnvelope.unsigned(
                first.topicId(), first.publisherPublicKey(), first.sequenceNumber(), first.timestamp(), first.payload());

        assertEquals(first.eventId(), EventCrypto.eventId(sameBody));
        assertEquals(EventCrypto.publisherKeyId(keyPair.getPublic()), EventCrypto.publisherKeyId(first));
        assertTrue(EventCrypto.verify(first));

        EventEnvelope modifiedPayload = new EventEnvelope(
                first.protocolVersion(),
                first.topicId(),
                first.publisherPublicKey(),
                first.sequenceNumber(),
                first.timestamp(),
                Base64.getEncoder().encodeToString("changed".getBytes()),
                first.signature(),
                first.eventId()
        );
        assertFalse(EventCrypto.verify(modifiedPayload));

        EventEnvelope modifiedMetadata = new EventEnvelope(
                first.protocolVersion(),
                first.topicId(),
                first.publisherPublicKey(),
                first.sequenceNumber() + 1,
                first.timestamp(),
                first.payload(),
                first.signature(),
                first.eventId()
        );
        assertFalse(EventCrypto.verify(modifiedMetadata));
    }

    @Test
    void sequenceStateSurvivesRestart() throws Exception {
        KeyPair keyPair = keyPair();
        EventPublisher firstPublisher = new EventPublisher(keyPair, CLOCK, tempDir);

        EventEnvelope first = firstPublisher.publish(TOPIC_ID, "one".getBytes());
        EventEnvelope second = new EventPublisher(keyPair, CLOCK, tempDir).publish(TOPIC_ID, "two".getBytes());

        assertEquals(0, first.sequenceNumber());
        assertEquals(1, second.sequenceNumber());
    }

    @Test
    void persistedEventsAdvanceSequenceWhenStateFileLags() throws Exception {
        KeyPair keyPair = keyPair();
        EventPublisher firstPublisher = new EventPublisher(keyPair, CLOCK, tempDir);
        firstPublisher.publish(TOPIC_ID, "one".getBytes());
        java.nio.file.Files.delete(tempDir.resolve("event-sequences.json"));

        EventEnvelope second = new EventPublisher(keyPair, CLOCK, tempDir).publish(TOPIC_ID, "two".getBytes());

        assertEquals(1, second.sequenceNumber());
    }

    @Test
    void validatorHandlesOpenModeratedDeletedDuplicatesAndConflicts() throws Exception {
        KeyPair allowed = keyPair();
        KeyPair unregistered = keyPair();
        EventEnvelope allowedEvent = new EventPublisher(allowed, CLOCK, tempDir.resolve("allowed")).publish(TOPIC_ID, "ok".getBytes());
        EventEnvelope unregisteredEvent = new EventPublisher(unregistered, CLOCK, tempDir.resolve("other")).publish(TOPIC_ID, "no".getBytes());
        String allowedPublisher = EventCrypto.publisherKeyId(allowed.getPublic());

        EventValidator openValidator = validator(topic(List.of(), true));
        assertTrue(openValidator.validate(unregisteredEvent).accepted());

        EventValidator moderatedValidator = validator(topic(List.of(allowedPublisher), true));
        assertTrue(moderatedValidator.validate(allowedEvent).accepted());
        assertEquals(EventRejectReason.UNAUTHORIZED_PUBLISHER, moderatedValidator.validate(unregisteredEvent).reason());
        assertEquals(EventSequenceStatus.DUPLICATE, moderatedValidator.validate(allowedEvent).sequenceStatus());

        EventEnvelope conflict = new EventPublisher(allowed, CLOCK, tempDir.resolve("conflict")).publish(TOPIC_ID, "different".getBytes());
        assertEquals(EventRejectReason.INVALID_SEQUENCE, moderatedValidator.validate(conflict).reason());

        EventValidator deletedValidator = validator(topic(List.of(), false));
        assertEquals(EventRejectReason.INACTIVE_TOPIC, deletedValidator.validate(unregisteredEvent).reason());
    }

    private EventValidator validator(TopicState topic) {
        Map<TopicId, TopicState> topics = Map.of(topic.topicId(), topic);
        return new EventValidator(topicId -> Optional.ofNullable(topics.get(topicId)), new EventDeduplicator(100));
    }

    private static TopicState topic(List<String> publishers, boolean active) {
        return new TopicState(TOPIC_ID, "orders", List.of("owner"), List.of(), publishers, 1, 60, active);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
