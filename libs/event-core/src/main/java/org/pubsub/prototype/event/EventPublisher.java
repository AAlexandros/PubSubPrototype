package org.pubsub.prototype.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.pubsub.prototype.registry.TopicId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Base64;

public final class EventPublisher {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final KeyPair keyPair;
    private final Clock clock;
    private final EventSequenceStore sequenceStore;
    private final Path eventDir;

    public EventPublisher(KeyPair keyPair, Clock clock, Path runtimeDir) {
        this.keyPair = keyPair;
        this.clock = clock;
        this.sequenceStore = new EventSequenceStore(runtimeDir);
        this.eventDir = runtimeDir.resolve("events");
        try {
            Files.createDirectories(eventDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize event storage at " + eventDir, ex);
        }
    }

    public EventEnvelope publish(TopicId topicId, byte[] payload) {
        String publisherPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String publisherKeyId = EventCrypto.publisherKeyId(keyPair.getPublic());
        long sequenceNumber = sequenceStore.reserve(topicId.value(), publisherKeyId);
        EventEnvelope unsigned = EventEnvelope.unsigned(
                topicId.value(),
                publisherPublicKey,
                sequenceNumber,
                clock.millis(),
                Base64.getEncoder().encodeToString(payload)
        );
        String eventId = EventCrypto.eventId(unsigned);
        EventEnvelope signed = unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keyPair.getPrivate()), eventId);
        persist(signed, publisherKeyId);
        sequenceStore.commit(topicId.value(), publisherKeyId, sequenceNumber);
        return signed;
    }

    public long nextSequence(TopicId topicId) {
        return sequenceStore.nextSequence(topicId.value(), EventCrypto.publisherKeyId(keyPair.getPublic()));
    }

    public String publisherKeyId() {
        return EventCrypto.publisherKeyId(keyPair.getPublic());
    }

    private void persist(EventEnvelope event, String publisherKeyId) {
        try {
            Path directory = eventDir.resolve(event.topicId()).resolve(publisherKeyId);
            Files.createDirectories(directory);
            MAPPER.writeValue(directory.resolve(event.sequenceNumber() + "-" + event.eventId() + ".json").toFile(), event);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist event before transmission", ex);
        }
    }
}
