package org.pubsub.prototype.replication.service;

import static org.pubsub.prototype.constants.PersistenceConstants.SINCE_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;

import java.util.List;
import java.util.Optional;

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
import org.pubsub.prototype.registry.replication.ReplicationMembershipProvider;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.replication.health.PeerFailureDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and routes event writes, event lookups, and publisher-progress
 * operations across replication servers. It also validates records stored or
 * fetched through the internal replica APIs.
 *
 * <p>
 * An event is assigned by its event key; a topic's publisher-progress log is
 * assigned by its topic ID, so the two records can have different responsible
 * servers. Placement uses the topic's replication factor and active membership,
 * excluding peers confirmed down by the shared failure detector. Any server
 * can act as the entry point for a request.
 */
public final class ReplicationService {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationService.class);

    // Also try one nearby server when membership views briefly differ.
    private static final int LOOKUP_EXTRA_CANDIDATES = 1;

    // The identity and advertised address of the replication-server instance
    // running this service
    private final ReplicationServerEndpoint localServer;

    // Resolve topic state and active replication servers from the current registry views.
    private final TopicStateProvider topicStateProvider;
    private final ReplicationMembershipProvider replicationMembershipProvider;
    // Send requests to other replication servers over HTTP.
    private final ReplicationHttpClient httpClient;

    private final FileEventStore store;
    private final PeerFailureDetector failureDetector;
    private final Authenticator<PublisherProgressAuthenticator.Request, TopicState> progressAuthenticator;
    private final PersistenceEventValidator validator;

    /**
     * Uses the same membership, topic source, and failure state as replica
     * maintenance.
     */
    public ReplicationService(ReplicationServerEndpoint self, ReplicationMembershipProvider membership,
            PersistenceEventValidator validator,
            TopicStateProvider topics, FileEventStore store, ReplicationHttpClient httpClient,
            PeerFailureDetector failureDetector) {
        this.localServer = self;
        this.replicationMembershipProvider = membership;
        this.validator = validator;
        this.topicStateProvider = topics;
        this.progressAuthenticator = new PublisherProgressAuthenticator(topics);
        this.store = store;
        this.httpClient = httpClient;
        this.failureDetector = failureDetector;
    }

    // Operations that require remote communication with other replication servers

    /**
     * Validates a published event, stores it on its responsible servers, then
     * advances the publisher's entry in the separately assigned topic log.
     *
     * @return the event record sent to the event servers
     */
    public StoredEvent persistEvent(EventEnvelope event) {
        String eventKey = EventKeys.eventKey(event);
        String publisher = EventCrypto.publisherKeyId(event);
        LOG.info(
                "EVENT_PERSIST_REQUESTED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                eventKey, event.eventId(), event.topicId(), publisher, event.sequenceNumber(), localServer.serverId());

        TopicState topic = validator.validate(event);
        List<ReplicationServerEndpoint> servers = activeHealthyServers();
        List<ReplicationServerEndpoint> targetServers = DhtAssignment.responsibleServers(eventKey, servers,
                topic.replicationFactor());
        if (targetServers.isEmpty())
            throw new IllegalStateException("no active replication servers");

        StoredEvent record = store.createEventRecord(event, topic);
        for (ReplicationServerEndpoint targetServer : targetServers) {
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

    /**
     * Looks up an event among its responsible servers and up to
     * LOOKUP_EXTRA_CANDIDATES extra nearby servers.
     *
     * A returned record must match the requested key and topic; its event ID,
     * signature, and publisher authorization are checked before it is returned.
     * Failed or invalid candidates are skipped.
     *
     * @return a valid event, or empty if none of the candidates supplies one
     */
    public Optional<StoredEvent> lookupEvent(String topicId, String eventKey) {
        LOG.info("EVENT_LOOKUP_REQUESTED eventKey={} serverId={}", eventKey, localServer.serverId());
        TopicState topic = topicState(topicId);
        List<ReplicationServerEndpoint> servers = activeHealthyServers();
        if (servers.isEmpty())
            return Optional.empty();
        // Search the R responsible replicas, then one nearby server for brief
        // membership-view differences.
        int candidates = Math.min(servers.size(), topic.replicationFactor() + LOOKUP_EXTRA_CANDIDATES);
        for (ReplicationServerEndpoint candidate : DhtAssignment.responsibleServers(eventKey, servers, candidates)) {
            try {
                Optional<StoredEvent> found = candidate.serverId().equals(localServer.serverId())
                        ? store.findUnexpiredEventOrDeleteExpiredFile(eventKey)
                        : httpClient.lookupLocal(candidate, eventKey);
                if (found.isPresent()) {
                    StoredEvent event = found.orElseThrow();
                    EventEnvelope envelope = event.eventEnvelope();
                    if (!eventKey.equals(event.eventKey())
                            || !eventKey.equals(EventKeys.eventKey(envelope))
                            || !topicId.equals(envelope.topicId()))
                        continue;
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

    // Local operations

    /**
     * Validates an incoming event replica before writing it locally. The event key
     * must place this server in the topic's current responsible replica set.
     *
     * @return the record stored with this server's current topic retention period
     */
    public StoredEvent storeReplica(StoredEvent record) {
        TopicState topic = validator.validate(record.eventEnvelope());
        require(DhtAssignment.responsibleServers(record.eventKey(), activeHealthyServers(), topic.replicationFactor())
                        .stream().anyMatch(server -> server.serverId().equals(localServer.serverId())),
                "server is not responsible for event replica");
        StoredEvent stored = store.storeEventReplica(record);
        EventEnvelope event = record.eventEnvelope();

        LOG.info(
                "EVENT_REPLICA_STORED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                record.eventKey(), event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event),
                event.sequenceNumber(), localServer.serverId());
        return stored;
    }

    /**
     * Reads only this server's event store, without routing or event validation.
     */
    public Optional<StoredEvent> lookupEventLocally(String eventKey) {
        return store.findUnexpiredEventOrDeleteExpiredFile(eventKey);
    }

    /** Returns all publisher progress for the topic. */
    public List<PublisherProgress> listPublisherProgress(String topicId) {
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
        for (ReplicationServerEndpoint candidate : DhtAssignment.responsibleServers(key, activeHealthyServers(),
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
        TopicState topic = topicStateProvider.findTopicState(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        return topic;
    }

    /** Builds the current routing view, excluding peers confirmed down. */
    private List<ReplicationServerEndpoint> activeHealthyServers() {
        var confirmedDown = failureDetector.confirmedDown();
        return replicationMembershipProvider.activeServers().stream()
                .filter(server -> !confirmedDown.contains(server.serverId())).toList();
    }

    /** Sends one publisher update to the servers responsible for the topic log. */
    private void replicatePublisherProgress(String topicId, int factor, PublisherProgress progress,
            List<ReplicationServerEndpoint> servers) {
        for (ReplicationServerEndpoint targetServer : DhtAssignment.responsibleServers(EventKeys.topicLogKey(topicId),
                servers,
                factor)) {
            if (targetServer.serverId().equals(localServer.serverId()))
                storeProgressReplica(topicId, progress);
            else
                httpClient.storeProgressReplica(targetServer, topicId, progress);
        }
    }

}
