package org.pubsub.prototype.replication.service;

import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.PublisherProgressAuthenticator;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.DhtAssignment;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.pubsub.prototype.constants.PersistenceConstants.SINCE_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;

/**
 * Routes event and publisher-progress operations across replication servers.
 *
 * <p>An event is assigned by its event key; a topic's publisher-progress log is
 * assigned by its topic ID. Consequently, the two records can have different
 * responsible servers. Each assignment uses the topic's replication factor and
 * the active membership, excluding peers confirmed down by the shared failure
 * detector. This server can accept a request even when it is not one of the
 * responsible servers.
 */
public final class ReplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationService.class);
    // Also try one nearby server when membership views briefly differ.
    private static final int LOOKUP_EXTRA_CANDIDATES = 1;

    private final ReplicationServer localServer;
    private final ReplicationMembership membership;
    private final PersistenceEventValidator validator;
    private final Authenticator<PublisherProgressAuthenticator.Request, TopicState> progressAuthenticator;
    private final TopicStateProvider topics;
    private final FileEventStore store;
    private final ReplicationHttpClient httpClient;
    private final PeerFailureDetector failureDetector;

    /** Uses the same membership, topic source, and failure state as replica maintenance. */
    public ReplicationService(ReplicationServer self, ReplicationMembership membership,
            PersistenceEventValidator validator,
            TopicStateProvider topics, FileEventStore store, ReplicationHttpClient httpClient,
            PeerFailureDetector failureDetector) {
        this.localServer = self;
        this.membership = membership;
        this.validator = validator;
        this.topics = topics;
        this.progressAuthenticator = new PublisherProgressAuthenticator(topics);
        this.store = store;
        this.httpClient = httpClient;
        this.failureDetector = failureDetector;
    }

    /**
     * Validates a published event, stores it on its responsible servers, then
     * advances the publisher's entry in the separately assigned topic log.
     *
     * @return the event record sent to the event servers
     */
    public StoredEvent persist(EventEnvelope event) {
        String eventKey = EventKeys.eventKey(event);
        String publisher = EventCrypto.publisherKeyId(event);
        LOG.info(
                "EVENT_PERSIST_REQUESTED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                eventKey, event.eventId(), event.topicId(), publisher, event.sequenceNumber(), localServer.serverId());
              
        TopicState topic = validator.validate(event);
        List<ReplicationServer> servers = activeHealthyServers();
        List<ReplicationServer> targetServers = DhtAssignment.responsibleServers(eventKey, servers,
                topic.replicationFactor());
        if (targetServers.isEmpty())
            throw new IllegalStateException("no active replication servers");


        StoredEvent record = store.newRecord(event, topic);
        for (ReplicationServer targetServer : targetServers) {
            if (targetServer.serverId().equals(localServer.serverId()))
                storeReplica(record);
            else
                httpClient.storeReplica(targetServer, record);
        }
        PublisherProgress progress = PublisherProgress.fromEvent(event);
        replicatePublisherProgress(event.topicId(), topic.replicationFactor(), progress, servers);
        LOG.info(
                "EVENT_PERSISTED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={} replicaCount={}",
                eventKey, event.eventId(), event.topicId(), publisher, event.sequenceNumber(), localServer.serverId(),
                targetServers.size());
        return record;
    }

    /** Validates an incoming event replica before writing it to local storage. */
    public void storeReplica(StoredEvent record) {
        validator.validate(record.eventEnvelope());
        store.storeReplica(record);
        EventEnvelope event = record.eventEnvelope();
        LOG.info(
                "EVENT_REPLICA_STORED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                record.eventKey(), event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event),
                event.sequenceNumber(), localServer.serverId());
    }

    /**
     * Looks up an event among its responsible servers and up to LOOKUP_EXTRA_CANDIDATES extra nearby servers.
     * A returned record must match the requested key and topic; its event ID,
     * signature, and publisher authorization are checked before it is returned.
     * Failed or invalid candidates are skipped.
     *
     * @return a valid event, or empty if none of the candidates supplies one
     */
    public Optional<StoredEvent> lookup(String topicId, String eventKey) {
        LOG.info("EVENT_LOOKUP_REQUESTED eventKey={} serverId={}", eventKey, localServer.serverId());
        TopicState topic = topicState(topicId);
        List<ReplicationServer> servers = activeHealthyServers();
        if (servers.isEmpty()) return Optional.empty();
        // Search the R responsible replicas, then one nearby server for brief membership-view differences.
        int candidates = Math.min(servers.size(), topic.replicationFactor() + LOOKUP_EXTRA_CANDIDATES);
        for (ReplicationServer candidate : DhtAssignment.responsibleServers(eventKey, servers, candidates)) {
            try {
                Optional<StoredEvent> found = candidate.serverId().equals(localServer.serverId())
                        ? store.get(eventKey)
                        : httpClient.lookupLocal(candidate, eventKey);
                if (found.isPresent()) {
                    StoredEvent event = found.orElseThrow();
                    EventEnvelope envelope = event.eventEnvelope();
                    if (!eventKey.equals(event.eventKey())
                            || !eventKey.equals(EventKeys.eventKey(envelope))
                            || !topicId.equals(envelope.topicId())) continue;
                    validator.validate(envelope);
                    LOG.info(
                            "EVENT_LOOKUP_HIT eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                            eventKey, event.eventEnvelope().eventId(), event.eventEnvelope().topicId(),
                            EventCrypto.publisherKeyId(event.eventEnvelope()), event.eventEnvelope().sequenceNumber(),
                            localServer.serverId());
                    return found;
                }
            } catch (RuntimeException ignored) {
                // Responsible-server failover continues in deterministic order.
            }
        }
        LOG.info("EVENT_LOOKUP_MISS eventKey={} serverId={}", eventKey, localServer.serverId());
        return Optional.empty();
    }

    /** Reads only this server's event store, without routing or event validation. */
    public Optional<StoredEvent> lookupLocal(String eventKey) {
        return store.get(eventKey);
    }

    /** Returns all publisher progress for the topic. */
    public List<PublisherProgress> publisherProgress(String topicId) {
        return publisherProgress(topicId, 0);
    }

    /**
     * Reads the first nonempty responsible topic log and returns publishers whose
     * latest event timestamp is greater than {@code sinceTimestamp}. A value of
     * zero disables the filter, including entries at timestamp zero. The topic
     * must exist and be active. 
     * 
     * Each returned entry carries its publisher's signature.
     */
    public List<PublisherProgress> publisherProgress(String topicId, long sinceTimestamp) {
        requireNonNegative(sinceTimestamp, SINCE_TIMESTAMP_FIELD);
        String key = EventKeys.topicLogKey(topicId);
        TopicState topic = topicState(topicId);
        for (ReplicationServer candidate : DhtAssignment.responsibleServers(key, activeHealthyServers(),
                topic.replicationFactor())) {
            try {
                List<PublisherProgress> values = candidate.serverId().equals(localServer.serverId())
                        ? store.publisherProgress(topicId)
                        : httpClient.publisherProgress(candidate, topicId, true);
                List<PublisherProgress> matching = values.stream()
                        .filter(value -> topicId.equals(value.topicId())).toList();
                if (!matching.isEmpty())
                    return matching.stream()
                            .filter(value -> sinceTimestamp == 0 || value.latestTimestamp() > sinceTimestamp).toList();
            } catch (RuntimeException ignored) {
                // Try the next responsible topic-log replica.
            }
        }
        return List.of();
    }

    /**
     * Reads this server's local publisher-progress file for the topic. It does not
     * contact other servers; if this server has no copy, the result is empty.
     */
    public List<PublisherProgress> localPublisherProgress(String topicId) {
        return store.publisherProgress(topicId);
    }

    /**
     * Applies a publisher-progress entry to the local topic log. The store keeps
     * the higher sequence number, or the later timestamp for an equal sequence.
     * The signature and topic are checked before updating the local log.
     */
    public void storeProgressReplica(String topicId, PublisherProgress progress) {
        progressAuthenticator.authenticate(new PublisherProgressAuthenticator.Request(topicId, progress));
        if (store.updateProgress(topicId, progress)) {
            LOG.info("TOPIC_LOG_UPDATED topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                    topicId, progress.publisherKeyId(), progress.latestSequenceNumber(), localServer.serverId());
        }
    }

    /** Gets the current topic state and rejects unknown or inactive topics. */
    TopicState topicState(String topicId) {
        TopicState topic = topics.topic(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        return topic;
    }

    /** Builds the current routing view, excluding peers confirmed down. */
    private List<ReplicationServer> activeHealthyServers() {
        var confirmedDown = failureDetector.confirmedDown();
        return membership.activeServers().stream()
                .filter(server -> !confirmedDown.contains(server.serverId())).toList();
    }

    /** Sends one publisher update to the servers responsible for the topic log. */
    private void replicatePublisherProgress(String topicId, int factor, PublisherProgress progress,
            List<ReplicationServer> servers) {
        for (ReplicationServer targetServer : DhtAssignment.responsibleServers(EventKeys.topicLogKey(topicId), servers,
                factor)) {
            if (targetServer.serverId().equals(localServer.serverId()))
                storeProgressReplica(topicId, progress);
            else
                httpClient.storeProgressReplica(targetServer, topicId, progress);
        }
    }

}
