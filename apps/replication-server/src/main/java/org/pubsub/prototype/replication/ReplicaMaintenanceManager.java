package org.pubsub.prototype.replication;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.EventReplicaMetadata;
import org.pubsub.prototype.persistence.MaintenanceStatus;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicaInventory;
import org.pubsub.prototype.persistence.ReplicaRecordType;
import org.pubsub.prototype.persistence.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.ReplicaRepairStatus;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.TopicLogReplicaMetadata;
import org.pubsub.prototype.persistence.core.DhtAssignment;
import org.pubsub.prototype.persistence.core.FileEventStore;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.pubsub.prototype.registry.TopicState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Supplier;

/** Decentralized, idempotent repair/release control loop for event and topic-log replicas. */
final class ReplicaMaintenanceManager {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicaMaintenanceManager.class);
    private static final int MAX_INVENTORY_RECORDS = 10_000;

    private final ReplicationServer self;
    private final ReplicationMembership membership;
    private final ReplicationService service;
    private final FileEventStore store;
    private final ReplicationHttpClient http;
    private final Supplier<List<TopicState>> topicSnapshot;
    private final Duration probeTimeout;
    private final PeerFailureDetector failures;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, RepairTask> repairs = new ConcurrentHashMap<>();
    private final Set<String> underReplicated = new ConcurrentSkipListSet<>();
    private volatile List<ReplicationServer> lastMembers = List.of();
    private volatile String membershipVersion = versionOf(List.of());

    ReplicaMaintenanceManager(ReplicationServer self, ReplicationMembership membership, ReplicationService service,
                              FileEventStore store, ReplicationHttpClient http,
                              Supplier<List<TopicState>> topicSnapshot, int failureProbeAttempts,
                              Duration probeTimeout) {
        this.self = self;
        this.membership = membership;
        this.service = service;
        this.store = store;
        this.http = http;
        this.topicSnapshot = topicSnapshot;
        this.failures = new PeerFailureDetector(failureProbeAttempts);
        this.probeTimeout = probeTimeout;
    }

    void runOnce() {
        List<ReplicationServer> members = normalized(membership.activeServers());
        String observedVersion = versionOf(members);
        refreshTopics();
        if (!observedVersion.equals(membershipVersion)) {
            List<ReplicationServer> old = lastMembers;
            lastMembers = members;
            membershipVersion = observedVersion;
            failures.retain(serverIds(members));
            service.retainUnavailable(serverIds(members));
            LOG.info("REPLICATION_MEMBERSHIP_CHANGED serverId={} membershipVersion={} oldReplicaSet={} newReplicaSet={}",
                    self.serverId(), membershipVersion, ids(old), ids(members));
            joinSynchronize(members, observedVersion);
        }
        probeRelevantPeers(members);
        Map<String, ReplicaInventory> inventories = new LinkedHashMap<>();
        reconcileLocalRecords(members, observedVersion, inventories);
        drainRepairs(members, observedVersion);
    }

    MaintenanceStatus status() {
        return new MaintenanceStatus(self.serverId(), membershipVersion,
                new ArrayList<>(failures.suspected()), new ArrayList<>(failures.confirmedDown()),
                repairs.values().stream().map(RepairTask::status).toList(), new ArrayList<>(underReplicated));
    }

    ReplicaInventory inventory() {
        List<EventReplicaMetadata> events = store.eventRecords().stream().limit(MAX_INVENTORY_RECORDS)
                .map(record -> new EventReplicaMetadata(record.eventKey(), record.eventEnvelope().topicId(),
                        EventCrypto.publisherKeyId(record.eventEnvelope()), record.eventEnvelope().sequenceNumber(),
                        record.topicRetentionPeriod(), record.storedAtEpoch(), record.expiresAfterEpoch()))
                .toList();
        List<TopicLogReplicaMetadata> logs = store.topicIds().stream().limit(MAX_INVENTORY_RECORDS)
                .map(topicId -> new TopicLogReplicaMetadata(topicId, store.publisherProgress(topicId))).toList();
        return new ReplicaInventory(self.serverId(), membershipVersion, events, logs);
    }

    void requestRepair(ReplicaRepairRequest request) {
        for (String unavailable : request.unavailableServerIds()) {
            if (failures.confirmReported(unavailable) == PeerFailureDetector.Transition.CONFIRMED_DOWN) {
                service.confirmUnavailable(unavailable);
                LOG.info("REPLICATION_PEER_CONFIRMED_DOWN serverId={} peerServerId={} reason=failure_notification",
                        self.serverId(), unavailable);
            }
        }
        if (request.recordType() == ReplicaRecordType.EVENT && store.get(request.recordKey()).isPresent()) return;
        enqueue(request.recordType(), request.recordKey(), request.topicId(), request.membershipVersion(),
                request.survivingReplicas(), request.responsibleServerIds(), request.reason());
    }

    private void refreshTopics() {
        try {
            Map<String, TopicState> observed = new LinkedHashMap<>();
            for (TopicState topic : topicSnapshot.get()) observed.put(topic.topicId().value(), topic);
            observed.forEach((topicId, topic) -> {
                TopicState old = topics.get(topicId);
                if (old != null && old.replicationFactor() != topic.replicationFactor()) {
                    LOG.info("REPLICATION_FACTOR_CHANGED serverId={} topicId={} oldReplicationFactor={} newReplicationFactor={}",
                            self.serverId(), topicId, old.replicationFactor(), topic.replicationFactor());
                }
            });
            topics.clear();
            topics.putAll(observed);
        } catch (RuntimeException ex) {
            LOG.warn("Topic registry maintenance sync failed: {}", message(ex));
        }
    }

    private void joinSynchronize(List<ReplicationServer> members, String version) {
        LOG.info("SERVER_JOIN_SYNC_STARTED serverId={} membershipVersion={}", self.serverId(), version);
        int discovered = 0;
        for (ReplicationServer peer : members) {
            if (peer.serverId().equals(self.serverId())) continue;
            try {
                ReplicaInventory inventory = http.inventory(peer);
                discovered += discoverAssignments(inventory, members, version, peer);
            } catch (RuntimeException ex) {
                LOG.debug("Join inventory unavailable peerServerId={} reason={}", peer.serverId(), message(ex));
            }
        }
        LOG.info("SERVER_JOIN_SYNC_COMPLETED serverId={} membershipVersion={} queuedRepairs={}",
                self.serverId(), version, discovered);
    }

    private int discoverAssignments(ReplicaInventory inventory, List<ReplicationServer> members, String version,
                                    ReplicationServer source) {
        int queued = 0;
        List<ReplicationServer> healthy = healthy(members);
        for (EventReplicaMetadata event : inventory.events()) {
            Optional<TopicState> topic = topic(event.topicId());
            if (topic.isEmpty() || store.get(event.eventKey()).isPresent()) continue;
            if (containsSelf(DhtAssignment.responsibleServers(event.eventKey(), healthy,
                    topic.orElseThrow().replicationFactor()))) {
                List<ReplicationServer> desired = DhtAssignment.responsibleServers(event.eventKey(), healthy,
                        topic.orElseThrow().replicationFactor());
                enqueue(ReplicaRecordType.EVENT, event.eventKey(), event.topicId(), version, List.of(source),
                        ids(desired), "join");
                queued++;
            }
        }
        for (TopicLogReplicaMetadata log : inventory.topicLogs()) {
            Optional<TopicState> topic = topic(log.topicId());
            String key = EventKeys.topicLogKey(log.topicId());
            if (topic.isEmpty()) continue;
            if (containsSelf(DhtAssignment.responsibleServers(key, healthy, topic.orElseThrow().replicationFactor()))) {
                List<ReplicationServer> desired = DhtAssignment.responsibleServers(key, healthy,
                        topic.orElseThrow().replicationFactor());
                enqueue(ReplicaRecordType.TOPIC_LOG, key, log.topicId(), version, List.of(source),
                        ids(desired), "join");
                queued++;
            }
        }
        return queued;
    }

    private void probeRelevantPeers(List<ReplicationServer> members) {
        Set<String> monitored = monitoredServerIds(members);
        for (ReplicationServer peer : members) {
            if (peer.serverId().equals(self.serverId()) || !monitored.contains(peer.serverId())) continue;
            PeerFailureDetector.Transition transition = http.probe(peer, probeTimeout)
                    ? failures.succeeded(peer.serverId()) : failures.failed(peer.serverId());
            switch (transition) {
                case SUSPECTED -> LOG.info("REPLICATION_PEER_SUSPECTED serverId={} peerServerId={}",
                        self.serverId(), peer.serverId());
                case CONFIRMED_DOWN -> {
                    service.confirmUnavailable(peer.serverId());
                    LOG.info("REPLICATION_PEER_CONFIRMED_DOWN serverId={} peerServerId={}",
                            self.serverId(), peer.serverId());
                }
                case RECOVERED -> {
                    service.confirmRecovered(peer.serverId());
                    LOG.info("REPLICATION_PEER_RECOVERED serverId={} peerServerId={}",
                            self.serverId(), peer.serverId());
                }
                case NONE -> { }
            }
        }
    }

    private Set<String> monitoredServerIds(List<ReplicationServer> members) {
        Set<String> monitored = new LinkedHashSet<>();
        for (StoredEvent record : store.eventRecords()) topic(record.eventEnvelope().topicId()).ifPresent(topic ->
                monitored.addAll(ids(DhtAssignment.responsibleServers(record.eventKey(), members,
                        topic.replicationFactor()))));
        for (String topicId : store.topicIds()) topic(topicId).ifPresent(topic ->
                monitored.addAll(ids(DhtAssignment.responsibleServers(EventKeys.topicLogKey(topicId), members,
                        topic.replicationFactor()))));
        monitored.remove(self.serverId());
        return monitored;
    }

    private void reconcileLocalRecords(List<ReplicationServer> members, String version,
                                       Map<String, ReplicaInventory> inventories) {
        underReplicated.clear();
        List<ReplicationServer> healthy = healthy(members);
        for (StoredEvent record : store.eventRecords()) {
            Optional<TopicState> topic = topic(record.eventEnvelope().topicId());
            if (topic.isEmpty()) continue;
            List<ReplicationServer> desired = DhtAssignment.responsibleServers(record.eventKey(), healthy,
                    topic.orElseThrow().replicationFactor());
            reconcileEvent(record, desired, version, inventories);
        }
        for (String topicId : store.topicIds()) {
            Optional<TopicState> topic = topic(topicId);
            if (topic.isEmpty()) continue;
            String key = EventKeys.topicLogKey(topicId);
            List<ReplicationServer> desired = DhtAssignment.responsibleServers(key, healthy,
                    topic.orElseThrow().replicationFactor());
            reconcileTopicLog(topicId, key, desired, version, inventories);
        }
    }

    private void reconcileEvent(StoredEvent record, List<ReplicationServer> desired, String version,
                                Map<String, ReplicaInventory> inventories) {
        List<ReplicationServer> sources = new ArrayList<>();
        sources.add(self);
        int holders = containsSelf(desired) ? 1 : 0;
        for (ReplicationServer target : desired) {
            if (target.serverId().equals(self.serverId())) continue;
            Optional<ReplicaInventory> inventory = inventory(target, inventories);
            boolean present = inventory.filter(value -> value.membershipVersion().equals(version))
                    .map(value -> hasEvent(value, record.eventKey())).orElse(false);
            if (present) {
                holders++;
                sources.add(target);
            } else {
                notifyRepair(target, ReplicaRecordType.EVENT, record.eventKey(), record.eventEnvelope().topicId(),
                        version, sources, desired, "reconciliation");
            }
        }
        if (holders < desired.size()) markUnderReplicated(ReplicaRecordType.EVENT, record.eventKey(), desired, holders);
        if (!containsSelf(desired) && !desired.isEmpty() && holders == desired.size()
                && version.equals(versionOf(normalized(membership.activeServers()))) && store.deleteEvent(record.eventKey())) {
            LOG.info("REPLICA_RELEASED serverId={} eventKey={} topicId={} oldReplicaSet={} newReplicaSet={} reason=placement_changed",
                    self.serverId(), record.eventKey(), record.eventEnvelope().topicId(), self.serverId(), ids(desired));
        }
    }

    private void reconcileTopicLog(String topicId, String key, List<ReplicationServer> desired, String version,
                                   Map<String, ReplicaInventory> inventories) {
        List<ReplicationServer> sources = new ArrayList<>();
        sources.add(self);
        int holders = containsSelf(desired) ? 1 : 0;
        for (ReplicationServer target : desired) {
            if (target.serverId().equals(self.serverId())) continue;
            Optional<ReplicaInventory> inventory = inventory(target, inventories);
            boolean present = inventory.filter(value -> value.membershipVersion().equals(version))
                    .map(value -> topicLogCovers(value, topicId, store.publisherProgress(topicId))).orElse(false);
            if (present) {
                holders++;
                sources.add(target);
            } else {
                notifyRepair(target, ReplicaRecordType.TOPIC_LOG, key, topicId, version, sources, desired,
                        "reconciliation");
            }
        }
        if (holders < desired.size()) markUnderReplicated(ReplicaRecordType.TOPIC_LOG, key, desired, holders);
        if (!containsSelf(desired) && !desired.isEmpty() && holders == desired.size()
                && version.equals(versionOf(normalized(membership.activeServers()))) && store.deleteTopicLog(topicId)) {
            LOG.info("REPLICA_RELEASED serverId={} eventKey={} topicId={} oldReplicaSet={} newReplicaSet={} reason=placement_changed",
                    self.serverId(), key, topicId, self.serverId(), ids(desired));
        }
    }

    private void notifyRepair(ReplicationServer target, ReplicaRecordType type, String key, String topicId,
                              String version, List<ReplicationServer> sources, List<ReplicationServer> desired,
                              String reason) {
        try {
            http.requestRepair(target, new ReplicaRepairRequest(type, key, topicId, version, sources, ids(desired),
                    new ArrayList<>(failures.confirmedDown()), reason));
        } catch (RuntimeException ex) {
            LOG.debug("Repair notification failed peerServerId={} eventKey={} reason={}",
                    target.serverId(), key, message(ex));
        }
    }

    private void markUnderReplicated(ReplicaRecordType type, String key, List<ReplicationServer> desired, int holders) {
        underReplicated.add(type + ":" + key);
        LOG.info("REPLICA_UNDER_REPLICATED serverId={} eventKey={} oldReplicaSet={} newReplicaSet={} replicaCount={} desiredReplicaCount={}",
                self.serverId(), key, self.serverId(), ids(desired), holders, desired.size());
    }

    private void drainRepairs(List<ReplicationServer> members, String version) {
        List<ReplicationServer> healthy = healthy(members);
        for (RepairTask task : new ArrayList<>(repairs.values())) {
            Optional<TopicState> topic = topic(task.topicId);
            if (topic.isEmpty()) continue;
            List<ReplicationServer> desired = task.membershipVersion.equals(version) && !task.responsibleServerIds.isEmpty()
                    ? members.stream().filter(server -> task.responsibleServerIds.contains(server.serverId())).toList()
                    : DhtAssignment.responsibleServers(task.key, healthy, topic.orElseThrow().replicationFactor());
            if (!containsSelf(desired)) {
                repairs.remove(task.id());
                continue;
            }
            LOG.info("REPLICA_REPAIR_STARTED serverId={} eventKey={} topicId={} reason={} oldReplicaSet={} newReplicaSet={}",
                    self.serverId(), task.key, task.topicId, task.reason, task.sourceIds(), ids(desired));
            task.attempts++;
            try {
                repair(task, members);
                repairs.remove(task.id());
                LOG.info("REPLICA_REPAIR_COMPLETED serverId={} eventKey={} topicId={} membershipVersion={}",
                        self.serverId(), task.key, task.topicId, version);
            } catch (RuntimeException ex) {
                task.lastError = message(ex);
                LOG.info("REPLICA_REPAIR_FAILED serverId={} eventKey={} topicId={} reason={}",
                        self.serverId(), task.key, task.topicId, task.lastError);
            }
        }
    }

    private void repair(RepairTask task, List<ReplicationServer> members) {
        RuntimeException last = null;
        for (ReplicationServer source : task.sources.values()) {
            if (source.serverId().equals(self.serverId()) || failures.confirmedDown().contains(source.serverId())
                    || members.stream().noneMatch(value -> value.serverId().equals(source.serverId()))) continue;
            try {
                if (task.type == ReplicaRecordType.EVENT) {
                    StoredEvent event = http.lookup(source, task.key, true)
                            .orElseThrow(() -> new IllegalStateException("surviving event replica is missing"));
                    LOG.info("REPLICA_REPAIR_FETCHED serverId={} peerServerId={} eventKey={} topicId={}",
                            self.serverId(), source.serverId(), task.key, task.topicId);
                    service.storeReplica(event);
                } else {
                    List<PublisherProgress> progress = http.publisherProgress(source, task.topicId, true);
                    if (progress.isEmpty()) throw new IllegalStateException("surviving topic log replica is missing");
                    LOG.info("REPLICA_REPAIR_FETCHED serverId={} peerServerId={} eventKey={} topicId={}",
                            self.serverId(), source.serverId(), task.key, task.topicId);
                    progress.forEach(value -> service.storeProgressReplica(task.topicId, value));
                }
                LOG.info("REPLICA_REPAIR_STORED serverId={} eventKey={} topicId={}",
                        self.serverId(), task.key, task.topicId);
                return;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw last == null ? new IllegalStateException("no surviving replica is available") : last;
    }

    private void enqueue(ReplicaRecordType type, String key, String topicId, String version,
                         Collection<ReplicationServer> sources, Collection<String> responsibleServerIds,
                         String reason) {
        String id = type + ":" + key;
        RepairTask task = repairs.computeIfAbsent(id,
                ignored -> new RepairTask(type, key, topicId, version, reason));
        sources.forEach(source -> task.sources.put(source.serverId(), source));
        task.responsibleServerIds.clear();
        task.responsibleServerIds.addAll(responsibleServerIds);
    }

    private Optional<ReplicaInventory> inventory(ReplicationServer server, Map<String, ReplicaInventory> cache) {
        if (cache.containsKey(server.serverId())) return Optional.ofNullable(cache.get(server.serverId()));
        try {
            ReplicaInventory value = http.inventory(server);
            cache.put(server.serverId(), value);
            return Optional.of(value);
        } catch (RuntimeException ex) {
            cache.put(server.serverId(), null);
            return Optional.empty();
        }
    }

    private Optional<TopicState> topic(String topicId) {
        TopicState cached = topics.get(topicId);
        if (cached != null && cached.active()) return Optional.of(cached);
        try {
            TopicState loaded = service.topic(topicId);
            topics.put(topicId, loaded);
            return Optional.of(loaded);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private List<ReplicationServer> healthy(List<ReplicationServer> members) {
        Set<String> down = failures.confirmedDown();
        return members.stream().filter(server -> !down.contains(server.serverId())).toList();
    }

    private boolean containsSelf(List<ReplicationServer> servers) {
        return servers.stream().anyMatch(server -> server.serverId().equals(self.serverId()));
    }

    private static boolean hasEvent(ReplicaInventory inventory, String key) {
        return inventory.events().stream().anyMatch(value -> value.eventKey().equals(key));
    }

    private static boolean topicLogCovers(ReplicaInventory inventory, String topicId,
                                          List<PublisherProgress> required) {
        return inventory.topicLogs().stream().filter(value -> value.topicId().equals(topicId)).findFirst()
                .map(log -> required.stream().allMatch(expected -> log.publishers().stream()
                        .filter(actual -> actual.publisherKeyId().equals(expected.publisherKeyId())).findFirst()
                        .map(actual -> actual.latestSequenceNumber() > expected.latestSequenceNumber()
                                || (actual.latestSequenceNumber() == expected.latestSequenceNumber()
                                && actual.latestTimestamp() >= expected.latestTimestamp()))
                        .orElse(false)))
                .orElse(false);
    }

    private static List<ReplicationServer> normalized(List<ReplicationServer> members) {
        return members.stream().sorted(Comparator.comparing(ReplicationServer::serverId)).toList();
    }

    private static Set<String> serverIds(List<ReplicationServer> servers) {
        return new TreeSet<>(ids(servers));
    }

    private static List<String> ids(Collection<ReplicationServer> servers) {
        return servers.stream().map(ReplicationServer::serverId).sorted().toList();
    }

    private static String versionOf(List<ReplicationServer> members) {
        ReplicationMembership snapshot = () -> members;
        return snapshot.version();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class RepairTask {
        private final ReplicaRecordType type;
        private final String key;
        private final String topicId;
        private final String membershipVersion;
        private final String reason;
        private final Map<String, ReplicationServer> sources = new ConcurrentHashMap<>();
        private final Set<String> responsibleServerIds = ConcurrentHashMap.newKeySet();
        private volatile int attempts;
        private volatile String lastError;

        private RepairTask(ReplicaRecordType type, String key, String topicId, String membershipVersion,
                           String reason) {
            this.type = type;
            this.key = key;
            this.topicId = topicId;
            this.membershipVersion = membershipVersion;
            this.reason = reason;
        }

        private String id() {
            return type + ":" + key;
        }

        private List<String> sourceIds() {
            return sources.keySet().stream().sorted().toList();
        }

        private ReplicaRepairStatus status() {
            return new ReplicaRepairStatus(type, key, topicId, membershipVersion, sourceIds(), attempts, lastError);
        }
    }
}
