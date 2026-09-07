package org.pubsub.prototype.node;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventDeduplicator;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.EventPublisher;
import org.pubsub.prototype.event.EventRejectReason;
import org.pubsub.prototype.event.EventSequenceStatus;
import org.pubsub.prototype.event.EventValidationResult;
import org.pubsub.prototype.event.EventValidator;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.transport.PubSubTransport;
import org.pubsub.prototype.transport.TransportListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;

final class NodeEventService implements TransportListener {
    private static final Logger LOG = LoggerFactory.getLogger(NodeEventService.class);

    private final EventPublisher publisher;
    private final EventValidator validator;
    private PubSubTransport transport;
    private DisseminationRuntime dissemination;
    private NodePersistenceRuntime persistence;

    NodeEventService(NodeIdentity identity, Path runtimeDir, TopicStateProvider topics) {
        this.publisher = new EventPublisher(identity.keyPair(), Clock.systemUTC(), runtimeDir);
        this.validator = new EventValidator(topics, new EventDeduplicator(10_000));
    }

    void attachTransport(PubSubTransport transport) {
        this.transport = transport;
    }

    void attachDissemination(DisseminationRuntime dissemination) {
        this.dissemination = dissemination;
    }

    void attachPersistence(NodePersistenceRuntime persistence) {
        this.persistence = persistence;
    }

    EventEnvelope publish(TopicId topicId, byte[] payload, boolean forceBroadcast, boolean tamperSignature) {
        EventEnvelope event = publisher.publish(topicId, payload);
        if (tamperSignature) {
            event = event.withSignature(Base64.getEncoder().encodeToString(new byte[64]));
        }
        EventValidationResult result = validator.validate(event);
        String publisherKeyId = result.publisherKeyId() == null ? EventCrypto.publisherKeyId(event) : result.publisherKeyId();
        if (result.accepted() || forceBroadcast) {
            LOG.info("EVENT_PUBLISHED eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
            if (result.accepted()) {
                LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId=local",
                        event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
                delivered(event, publisherKeyId, true);
            }
            if (forceBroadcast || dissemination == null) transport.broadcastEvent(event);
            else dissemination.disseminate(event, null);
        }
        if (!result.accepted() && !forceBroadcast) {
            logRejection(event, publisherKeyId, result.reason(), null);
            throw new IllegalArgumentException("Event rejected: " + result.reason());
        }
        return event;
    }

    void acceptRecovered(EventEnvelope event) {
        EventValidationResult result = validator.validate(event);
        String publisherKeyId = result.publisherKeyId();
        if (!result.accepted()) {
            if (result.sequenceStatus() == EventSequenceStatus.DUPLICATE) return;
            throw new IllegalArgumentException("Recovered event rejected: " + result.reason());
        }
        LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId=recovery",
                event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
    }

    EventEnvelope inject(EventEnvelope event) {
        LOG.info("EVENT_PUBLISHED eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber());
        if (dissemination == null) transport.broadcastEvent(event);
        else dissemination.disseminate(event, null);
        return event;
    }

    String publisherKeyId() {
        return publisher.publisherKeyId();
    }

    @Override
    public void eventReceived(NodeId peerNodeId, EventEnvelope event) {
        LOG.info("EVENT_RECEIVED eventId={} topicId={} sequenceNumber={} peerNodeId={}",
                event.eventId(), event.topicId(), event.sequenceNumber(), peerNodeId.value());
        EventValidationResult result = validator.validate(event);
        String publisherKeyId = result.publisherKeyId();
        if (result.accepted()) {
            LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), peerNodeId.value());
            // Remote acceptance is a delivery-only path. The original publisher is
            // solely responsible for submitting the event to persistence.
            delivered(event, publisherKeyId, false);
            if (dissemination == null) transport.forwardEvent(event, peerNodeId);
            else dissemination.disseminate(event, peerNodeId);
            LOG.info("EVENT_FORWARDED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), peerNodeId.value());
        } else if (result.sequenceStatus() == EventSequenceStatus.DUPLICATE) {
            LOG.info("EVENT_DUPLICATE eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), peerNodeId.value());
        } else {
            if (result.sequenceStatus() == EventSequenceStatus.CONFLICT) {
                LOG.info("EVENT_SEQUENCE_CONFLICT eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                        event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), peerNodeId.value());
            }
            logRejection(event, publisherKeyId, result.reason(), peerNodeId);
        }
    }

    private void delivered(EventEnvelope event, String publisherKeyId, boolean persist) {
        if (persistence != null) {
            persistence.recordDelivered(event, publisherKeyId);
            if (persist) persistence.persist(event);
        }
    }

    private void logRejection(EventEnvelope event, String publisherKeyId, EventRejectReason reason, NodeId peerNodeId) {
        LOG.info("EVENT_REJECTED reason={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                reason, event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(),
                peerNodeId == null ? null : peerNodeId.value());
    }
}
