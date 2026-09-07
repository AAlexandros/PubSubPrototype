package org.pubsub.prototype.replication;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.core.DhtAssignment;
import org.pubsub.prototype.persistence.core.FileEventStore;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.pubsub.prototype.registry.TopicState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ReplicationService {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationService.class);
    private final ReplicationServer self;
    private final ReplicationMembership membership;
    private final PersistenceEventValidator validator;
    private final FileEventStore store;
    private final ReplicationHttpClient http;
    private final Set<String> unavailableServers = ConcurrentHashMap.newKeySet();

    ReplicationService(ReplicationServer self, ReplicationMembership membership, PersistenceEventValidator validator,
                       FileEventStore store, ReplicationHttpClient http) {
        this.self = self;
        this.membership = membership;
        this.validator = validator;
        this.store = store;
        this.http = http;
    }

    StoredEvent persist(EventEnvelope event) {
        String eventKey = EventKeys.eventKey(event);
        String publisher = EventCrypto.publisherKeyId(event);
        LOG.info("EVENT_PERSIST_REQUESTED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                eventKey, event.eventId(), event.topicId(), publisher, event.sequenceNumber(), self.serverId());
        TopicState topic = validator.validate(event);
        List<ReplicationServer> servers = activeHealthyServers();
        List<ReplicationServer> replicas = DhtAssignment.responsibleServers(eventKey, servers, topic.replicationFactor());
        if (replicas.isEmpty()) throw new IllegalStateException("no active replication servers");
        StoredEvent record = store.newRecord(event, topic);
        for (ReplicationServer replica : replicas) {
            if (replica.serverId().equals(self.serverId())) storeReplica(record);
            else http.storeReplica(replica, record);
        }
        PublisherProgress progress = new PublisherProgress(publisher, event.sequenceNumber(), event.timestamp());
        replicateProgress(event.topicId(), topic.replicationFactor(), progress, servers);
        LOG.info("EVENT_PERSISTED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={} replicaCount={}",
                eventKey, event.eventId(), event.topicId(), publisher, event.sequenceNumber(), self.serverId(), replicas.size());
        return record;
    }

    void storeReplica(StoredEvent record) {
        validator.validate(record.eventEnvelope());
        store.storeReplica(record);
        EventEnvelope event = record.eventEnvelope();
        LOG.info("EVENT_REPLICA_STORED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                record.eventKey(), event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event),
                event.sequenceNumber(), self.serverId());
    }

    Optional<StoredEvent> lookup(String eventKey) {
        LOG.info("EVENT_LOOKUP_REQUESTED eventKey={} serverId={}", eventKey, self.serverId());
        List<ReplicationServer> servers = activeHealthyServers();
        for (ReplicationServer candidate : DhtAssignment.responsibleServers(eventKey, servers,
                Math.max(1, servers.size()))) {
            try {
                Optional<StoredEvent> found = candidate.serverId().equals(self.serverId())
                        ? store.get(eventKey) : http.lookup(candidate, eventKey, true);
                if (found.isPresent()) {
                    StoredEvent event = found.orElseThrow();
                    LOG.info("EVENT_LOOKUP_HIT eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                            eventKey, event.eventEnvelope().eventId(), event.eventEnvelope().topicId(),
                            EventCrypto.publisherKeyId(event.eventEnvelope()), event.eventEnvelope().sequenceNumber(), self.serverId());
                    return found;
                }
            } catch (RuntimeException ignored) {
                // Responsible-server failover continues in deterministic order.
            }
        }
        LOG.info("EVENT_LOOKUP_MISS eventKey={} serverId={}", eventKey, self.serverId());
        return Optional.empty();
    }

    Optional<StoredEvent> lookupLocal(String eventKey) {
        return store.get(eventKey);
    }

    List<PublisherProgress> publisherProgress(String topicId) {
        return publisherProgress(topicId, 0);
    }

    List<PublisherProgress> publisherProgress(String topicId, long sinceTimestamp) {
        if (sinceTimestamp < 0) throw new IllegalArgumentException("sinceTimestamp must be non-negative");
        String key = EventKeys.topicLogKey(topicId);
        TopicState topic = validatorTopic(topicId);
        for (ReplicationServer candidate : DhtAssignment.responsibleServers(key, activeHealthyServers(), topic.replicationFactor())) {
            try {
                List<PublisherProgress> values = candidate.serverId().equals(self.serverId())
                        ? store.publisherProgress(topicId) : http.publisherProgress(candidate, topicId, true);
                if (!values.isEmpty()) return values.stream()
                        .filter(value -> value.latestTimestamp() > sinceTimestamp).toList();
            } catch (RuntimeException ignored) {
                // Try the next responsible topic-log replica.
            }
        }
        return List.of();
    }

    List<PublisherProgress> localPublisherProgress(String topicId) {
        return store.publisherProgress(topicId);
    }

    void storeProgressReplica(String topicId, PublisherProgress progress) {
        if (store.updateProgress(topicId, progress)) {
            LOG.info("TOPIC_LOG_UPDATED topicId={} publisherKeyId={} sequenceNumber={} serverId={}",
                    topicId, progress.publisherKeyId(), progress.latestSequenceNumber(), self.serverId());
        }
    }

    FileEventStore store() {
        return store;
    }

    TopicState topic(String topicId) {
        return validatorTopic(topicId);
    }

    void confirmUnavailable(String serverId) {
        unavailableServers.add(serverId);
    }

    void confirmRecovered(String serverId) {
        unavailableServers.remove(serverId);
    }

    void retainUnavailable(Set<String> activeServerIds) {
        unavailableServers.retainAll(activeServerIds);
    }

    private List<ReplicationServer> activeHealthyServers() {
        return membership.activeServers().stream()
                .filter(server -> !unavailableServers.contains(server.serverId())).toList();
    }

    private void replicateProgress(String topicId, int factor, PublisherProgress progress, List<ReplicationServer> servers) {
        for (ReplicationServer replica : DhtAssignment.responsibleServers(EventKeys.topicLogKey(topicId), servers, factor)) {
            if (replica.serverId().equals(self.serverId())) storeProgressReplica(topicId, progress);
            else http.storeProgressReplica(replica, topicId, progress);
        }
    }

    private TopicState validatorTopic(String topicId) {
        // A synthetic envelope is inappropriate here; topic lookup is exposed by the validator for this operation.
        return validator.topic(topicId);
    }
}
