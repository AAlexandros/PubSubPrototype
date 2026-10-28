package org.pubsub.prototype.node.service;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventDeduplicator;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.LocalEventCreator;
import org.pubsub.prototype.event.EventRejectReason;
import org.pubsub.prototype.event.EventSequenceStatus;
import org.pubsub.prototype.event.EventValidationResult;
import org.pubsub.prototype.event.EventValidator;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.registry.topic.TopicId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;

/**
 * Processes events published through this node or received from peers.
 * It validates each event, then coordinates forwarding and storage of accepted
 * events. It also validates events fetched during recovery.
 */
public final class EventProcessingService {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(EventProcessingService.class);

    private DisseminationService disseminationService;
    private EventStorageService eventStorageService;
    private final LocalEventCreator eventCreator;
    private final EventValidator validator;

    public EventProcessingService(NodeIdentity identity, Path runtimeDir, TopicStateProvider topics) {
        this(identity, runtimeDir, new EventValidator(topics, new EventDeduplicator(10_000)));
    }

    public EventProcessingService(NodeIdentity identity, Path runtimeDir, EventValidator validator) {
        this.eventCreator = new LocalEventCreator(identity.keyPair(), Clock.systemUTC(), runtimeDir);
        this.validator = validator;
        NodeLog.init(LOG, "EVENT_SERVICE_READY publisherKey={} runtimeDir={}",
                NodeLog.node(eventCreator.publisherKeyId()), runtimeDir);
    }

    // Methods for attaching services, after their initialization

    public void attachDissemination(DisseminationService dissemination) {
        this.disseminationService = dissemination;
    }

    public void attachEventStorage(EventStorageService eventStorage) {
        this.eventStorageService = eventStorage;
    }

    /**
     * Creates and validates an event published through this node, then starts its transmission.
     * Accepted events have their recovery progress saved locally and are queued for storage on
     * a replication server. Normal publication sends them to peers selected for the topic,
     * if any are known.
     * {@code forceBroadcast} is an acceptance-test control: it sends to all active peers
     * even after local rejection. Rejected events are not marked delivered or submitted
     * to replication.
     * {@code tamperSignature} replaces the signature after the signed copy is stored.
     * Returning an envelope does not confirm receipt by another node.
     */
    public EventEnvelope publishEvent(TopicId topicId, byte[] payload, boolean forceBroadcast, boolean tamperSignature) {
        LOG.info("EVENT_PUBLISH_IN topicId={} payloadBytes={} forceBroadcast={} tamperSignature={}",
                topicId, payload.length, forceBroadcast, tamperSignature);
        EventEnvelope event = eventCreator.createAndStoreSignedEvent(topicId, payload);
        if (tamperSignature) {
            event = event.withSignature(Base64.getEncoder().encodeToString(new byte[64]));
        }
        EventValidationResult result = validator.validate(event);
        LOG.info("EVENT_VALIDATED eventId={} accepted={} reason={} sequenceStatus={}",
                event.eventId(), result.accepted(), result.reason(), result.sequenceStatus());
        String publisherKeyId = result.publisherKeyId() == null ? EventCrypto.publisherKeyId(event) : result.publisherKeyId();
        if (result.accepted() || forceBroadcast) {
            LOG.info("EVENT_PUBLISHED eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
            if (result.accepted()) {
                LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId=local",
                        event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
                eventStorageService.saveLocalRecoveryProgress(event);
                eventStorageService.queueEventForRemoteStorage(event);
            }
            if (forceBroadcast) disseminationService.broadcastEventToConnectedPeers(event);
            else disseminationService.sendEventToTopicPeers(event, null);
        }
        if (!result.accepted() && !forceBroadcast) {
            logRejection(event, publisherKeyId, result.reason(), null);
            throw new IllegalArgumentException("Event rejected: " + result.reason());
        }
        LOG.info("EVENT_PUBLISH_OUT eventId={} topicId={} sequenceNumber={} route={}",
                event.eventId(), event.topicId(), event.sequenceNumber(), forceBroadcast ? "broadcast" : "dissemination");
        return event;
    }
    
    /** Applies the result of validation to an event received from a peer. */
    public void handleIncomingEvent(NodeId peerNodeId, EventEnvelope event, EventValidationResult result) {
        NodeLog.network(LOG, "EVENT_RECEIVED eventId={} topicId={} sequenceNumber={} peerNodeId={} accepted={} reason={}",
                event.eventId(), event.topicId(), event.sequenceNumber(), peerNodeId.value(),
                result.accepted(), result.reason());
        String publisherKeyId = result.publisherKeyId();
        if (result.accepted()) {
            LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                    event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), peerNodeId.value());
            // The original publisher is responsible for submitting the event for storage.
            eventStorageService.saveLocalRecoveryProgress(event);
            disseminationService.sendEventToTopicPeers(event, peerNodeId);
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

    /** Validates a recovered event before EventStorageService records its delivery. */
    public void validateRecoveredEvent(EventEnvelope event) {
        LOG.info("EVENT_RECOVERY_IN eventId={} topicId={} sequenceNumber={}",
                event.eventId(), event.topicId(), event.sequenceNumber());
        EventValidationResult result = validator.validate(event);
        String publisherKeyId = result.publisherKeyId();
        if (!result.accepted()) {
            if (result.sequenceStatus() == EventSequenceStatus.DUPLICATE) {
                LOG.info("EVENT_RECOVERY_OUT eventId={} result=duplicate", event.eventId());
                return;
            }
            throw new IllegalArgumentException("Recovered event rejected: " + result.reason());
        }
        LOG.info("EVENT_ACCEPTED eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId=recovery",
                event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
        LOG.info("EVENT_RECOVERY_OUT eventId={} result=accepted", event.eventId());
    }

    /** Returns the publisher key ID derived from this node's signing key. */
    public String getLocalPublisherKeyId() {
        String keyId = eventCreator.publisherKeyId();
        LOG.info("PUBLISHER_KEY_ID_OUT publisherKeyId={}", keyId);
        return keyId;
    }

    private void logRejection(EventEnvelope event, String publisherKeyId, EventRejectReason reason, NodeId peerNodeId) {
        LOG.info("EVENT_REJECTED reason={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} peerNodeId={}",
                reason, event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(),
                peerNodeId == null ? null : peerNodeId.value());
    }
}
