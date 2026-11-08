package org.pubsub.prototype.replication.service;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.DhtAssignment;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.maintenance.EventReplicaMetadata;
import org.pubsub.prototype.persistence.maintenance.MaintenanceStatus;
import org.pubsub.prototype.persistence.maintenance.ReplicaInventory;
import org.pubsub.prototype.persistence.maintenance.ReplicaRecordType;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairStatus;
import org.pubsub.prototype.persistence.maintenance.TopicLogReplicaMetadata;
import org.pubsub.prototype.registry.replication.ReplicationMembershipProvider;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.TopicStateSnapshotProvider;
import org.pubsub.prototype.replication.health.PeerFailureDetector;
import org.pubsub.prototype.service.CyclicService;
import org.pubsub.prototype.util.AsyncUtil;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps locally stored events and topic logs aligned with the servers that
 * should hold them. Placement depends on each topic's replication factor,
 * current replication server membership, and peers confirmed down locally.
 *
 * <p>One scheduled task removes expired events. The other runs a maintenance
 * cycle: refresh topic policy, inspect peer inventories when membership changes,
 * probe peers responsible for locally held records, reconcile local records,
 * and process queued repairs. Peers confirmed down are excluded from repair
 * placement, but continue to be probed for recovery while relevant.</p>
 *
 * <p>A server that leaves a record's responsible set retains its local copy
 * until every current owner reports the event key or sufficient publisher
 * progress under the current membership snapshot. Repair requires a
 * surviving source; this service cannot reconstruct a record after all
 * of its copies are lost.</p>
 */
public final class ReplicaMaintenanceService implements CyclicService {

    /** One pending record, with peers advertised as possible fetch sources. */
    private static final class RepairTask {
        private final ReplicaRecordType recordType;
        private final String recordKey;
        private final String topicId;
        private final String membershipVersion;
        private final Set<String> sourceServerIds = ConcurrentHashMap.newKeySet();
        private volatile int attempts;
        private volatile String lastError;

        private RepairTask(ReplicaRecordType recordType, String recordKey, String topicId, String membershipVersion) {
            this.recordType = recordType;
            this.recordKey = recordKey;
            this.topicId = topicId;
            this.membershipVersion = membershipVersion;
        }

        private String recordId() {
            return recordType + ":" + recordKey;
        }

        private List<String> sortedSourceServerIds() {
            return sourceServerIds.stream().sorted().toList();
        }

        private ReplicaRepairStatus status() {
            return new ReplicaRepairStatus(recordType, recordKey, topicId, membershipVersion,
                    sortedSourceServerIds(), attempts, lastError);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ReplicaMaintenanceService.class);

    /** Runs expiration cleanup and replica maintenance on separate scheduled threads. */
    private final ScheduledExecutorService executor = AsyncUtil.scheduledThreadPool("replication-maintenance", 2);

    private final long cleanupIntervalMs;
    private final long maintenanceIntervalMs;
    private final Duration probeTimeout;

    private final ReplicationService replicationService;

    private final TopicStateSnapshotProvider topicSnapshotProvider;
    private final ReplicationMembershipProvider replicationMembershipProvider;
    private final ReplicationHttpClient httpClient;

    private final FileEventStore eventStore;
    private final ReplicationServerEndpoint localServer;
    private final PeerFailureDetector failureDetector;
    /** Last observed topics, used for placement and replication-factor change logs. */
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    /** Repairs keyed by record type and key; repeated peer reports add source IDs. */
    private final Map<String, RepairTask> pendingRepairs = new ConcurrentHashMap<>();
    /** Records missing at least one responsible copy in the latest reconciliation. */
    private final Set<String> underReplicated = new ConcurrentSkipListSet<>();
    /** Previous membership for change logging; placement always reads the provider. */
    private volatile List<ReplicationServerEndpoint> lastMembers = List.of();
    /** Version of the membership most recently observed by the maintenance cycle. */
    private volatile String membershipVersion = ReplicationMembershipProvider.versionOf(List.of());

    public ReplicaMaintenanceService(ReplicationServerEndpoint localServer, ReplicationMembershipProvider membership,
            ReplicationService replicationService,
            FileEventStore eventStore, ReplicationHttpClient http,
            TopicStateSnapshotProvider topicSnapshotProvider, PeerFailureDetector failureDetector,
            Duration probeTimeout, long cleanupIntervalMs, long maintenanceIntervalMs) {
        this.localServer = localServer;
        this.replicationMembershipProvider = membership;
        this.replicationService = replicationService;
        this.eventStore = eventStore;
        this.httpClient = http;
        this.topicSnapshotProvider = topicSnapshotProvider;
        this.failureDetector = failureDetector;
        this.probeTimeout = probeTimeout;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.maintenanceIntervalMs = maintenanceIntervalMs;
    }

    /** Starts periodic expiration cleanup and replica maintenance. */
    @Override
    public void start() {
        // Start the cleanup thread
        executor.scheduleWithFixedDelay(() -> {
            try {
                eventStore.deleteExpiredEventFiles();
            } catch (RuntimeException ex) {
                LOG.error("REPLICA_EXPIRATION_CLEANUP reason=runtime_error", ex);
            }
        }, cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
        // Start the maintenance thread
        executor.scheduleWithFixedDelay(this::runCycle, 0, maintenanceIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs one maintenance pass. A failure is logged so subsequent scheduled
     * passes can still run; repairs that fail remain queued for a later pass.
     */
    @Override
    public void runCycle() {
        try {
            List<ReplicationServerEndpoint> activeServers = sortServersById(replicationMembershipProvider.activeServers());
            String observedVersion = ReplicationMembershipProvider.versionOf(activeServers);
            refreshTopics();
            if (!observedVersion.equals(membershipVersion)) {
                List<ReplicationServerEndpoint> old = lastMembers;
                lastMembers = activeServers;
                membershipVersion = observedVersion;
                failureDetector.retain(serverIdSet(activeServers));
                LOG.info(
                        "REPLICATION_MEMBERSHIP_CHANGED serverId={} membershipVersion={} oldReplicaSet={} newReplicaSet={}",
                        localServer.serverId(), membershipVersion, sortedServerIds(old), sortedServerIds(activeServers));
                queueRepairsAfterMembershipChange(activeServers, observedVersion);
            }
            probeRelevantPeers(activeServers);
            Map<String, ReplicaInventory> inventories = new LinkedHashMap<>();
            reconcileLocalRecords(activeServers, observedVersion, inventories);
            processQueuedRepairs(activeServers, observedVersion);
        } catch (RuntimeException ex) {
            LOG.error("REPLICA_MAINTENANCE_CYCLE reason=runtime_error", ex);
        }
    }

    /** Stops both scheduled tasks and interrupts any running maintenance work. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Returns the latest membership, peer health, pending repairs, and replica deficits. */
    public MaintenanceStatus status() {
        return new MaintenanceStatus(localServer.serverId(), membershipVersion,
                new ArrayList<>(failureDetector.suspected()), new ArrayList<>(failureDetector.confirmedDown()),
                pendingRepairs.values().stream().map(RepairTask::status).toList(), new ArrayList<>(underReplicated));
    }

    /** Lists locally stored event keys for the health response. */
    public List<String> localEventKeys() {
        return eventStore.eventKeys();
    }

    /**
     * Advertises locally stored events and topic-log progress to other servers.
     * The membership version lets callers reject an inventory from an older view.
     */
    public ReplicaInventory inventory() {
        List<EventReplicaMetadata> events = eventStore.eventRecords().stream()
                .map(record -> new EventReplicaMetadata(record.eventKey(), record.eventEnvelope().topicId(),
                        EventCrypto.publisherKeyId(record.eventEnvelope()), record.eventEnvelope().sequenceNumber(),
                        record.topicRetentionPeriod(), record.storedAtEpoch(), record.expiresAfterEpoch()))
                .toList();
        List<TopicLogReplicaMetadata> logs = eventStore.topicIds().stream()
                .map(topicId -> new TopicLogReplicaMetadata(topicId, eventStore.publisherProgress(topicId))).toList();
        return new ReplicaInventory(localServer.serverId(), membershipVersion, events, logs);
    }

    /**
     * Accepts a repair request from a peer. Reported unavailable servers are
     * probed locally before they affect placement. Existing unexpired events
     * need no repair; other requests add the supplied sources to the queue.
     */
    public void requestRepair(ReplicaRepairRequest request) {
        List<ReplicationServerEndpoint> activeServers = sortServersById(replicationMembershipProvider.activeServers());
        for (String unavailable : new LinkedHashSet<>(request.unavailableServerIds())) {
            activeServers.stream().filter(server -> !server.serverId().equals(localServer.serverId())
                    && server.serverId().equals(unavailable)).findFirst()
                    .ifPresent(this::verifyReportedFailure);
        }
        if (request.recordType() == ReplicaRecordType.EVENT
                && eventStore.findUnexpiredEventOrDeleteExpiredFile(request.recordKey()).isPresent())
            return;
        queueRepair(request.recordType(), request.recordKey(), request.topicId(),
                ReplicationMembershipProvider.versionOf(activeServers),
                request.sourceServerIds());
    }

    /** Verifies a peer's failure report with local probes instead of trusting it. */
    private void verifyReportedFailure(ReplicationServerEndpoint server) {
        if (failureDetector.confirmedDown().contains(server.serverId()))
            return;
        for (int attempt = 0; attempt < failureDetector.attemptsRequired(); attempt++) {
            if (httpClient.probe(server, probeTimeout)) {
                failureDetector.succeeded(server.serverId());
                return;
            }
            if (failureDetector.failed(server.serverId()) == PeerFailureDetector.Transition.CONFIRMED_DOWN) {
                LOG.info("REPLICATION_PEER_CONFIRMED_DOWN serverId={} peerServerId={}",
                        localServer.serverId(), server.serverId());
                return;
            }
        }
    }

    /**
     * Updates the topic cache from the registry snapshot before reconciliation.
     * Comparing with the previous snapshot lets us log replication-factor changes;
     * the rest of the maintenance cycle uses the updated factors to place replicas.
     * If the snapshot cannot be read, the previous cache is retained and the failure is logged.
     */
    private void refreshTopics() {
        try {
            Map<String, TopicState> observed = new LinkedHashMap<>();
            for (TopicState topic : topicSnapshotProvider.activeTopics())
                observed.put(topic.topicId().value(), topic);
            observed.forEach((topicId, topic) -> {
                TopicState old = topics.get(topicId);
                if (old != null && old.replicationFactor() != topic.replicationFactor()) {
                    LOG.info(
                            "REPLICATION_FACTOR_CHANGED serverId={} topicId={} oldReplicationFactor={} newReplicationFactor={}",
                            localServer.serverId(), topicId, old.replicationFactor(), topic.replicationFactor());
                }
            });
            topics.clear();
            topics.putAll(observed);
        } catch (RuntimeException ex) {
            LOG.warn("Topic registry maintenance sync failed: {}", errorMessage(ex));
        }
    }

    /**
     * Scans every peer's inventory after a membership change. Missing records
     * assigned to this server become repair candidates; each advertising peer
     * can contribute a source for the same queued repair.
     */
    private void queueRepairsAfterMembershipChange(List<ReplicationServerEndpoint> activeServers,
            String membershipSnapshotVersion) {
        LOG.info("SERVER_JOIN_SYNC_STARTED serverId={} membershipVersion={}",
                localServer.serverId(), membershipSnapshotVersion);
        int repairCandidates = 0;
        for (ReplicationServerEndpoint sourceServer : activeServers) {
            if (sourceServer.serverId().equals(localServer.serverId()))
                continue;
            try {
                ReplicaInventory sourceInventory = httpClient.inventory(sourceServer);
                repairCandidates += queueMissingReplicasFromPeerInventory(sourceServer, sourceInventory,
                        activeServers, membershipSnapshotVersion);
            } catch (RuntimeException ex) {
                LOG.debug("Join inventory unavailable peerServerId={} reason={}",
                        sourceServer.serverId(), errorMessage(ex));
            }
        }
        LOG.info("SERVER_JOIN_SYNC_COMPLETED serverId={} membershipVersion={} repairCandidates={}",
                localServer.serverId(), membershipSnapshotVersion, repairCandidates);
    }

    /**
     * Uses all active servers to calculate placement for records advertised by
     * {@code sourceServer}. When this server is responsible, it queues missing
     * events and topic-log synchronization using that peer as a fetch source.
     *
     * @return number of matching inventory records, including duplicates across peers
     */
    private int queueMissingReplicasFromPeerInventory(ReplicationServerEndpoint sourceServer,
            ReplicaInventory sourceInventory, List<ReplicationServerEndpoint> activeServers,
            String membershipSnapshotVersion) {

        int repairCandidates = 0;
        List<ReplicationServerEndpoint> availableServers = serversNotConfirmedDown(activeServers);
        for (EventReplicaMetadata eventMetadata : sourceInventory.events()) {
            if (eventStore.findUnexpiredEventOrDeleteExpiredFile(eventMetadata.eventKey()).isPresent())
                continue;

            if (queueRepairIfLocalServerIsResponsible(ReplicaRecordType.EVENT, eventMetadata.eventKey(),
                    eventMetadata.topicId(), membershipSnapshotVersion, sourceServer.serverId(), availableServers))
                repairCandidates++;
        }
        for (TopicLogReplicaMetadata topicLogMetadata : sourceInventory.topicLogs()) {
            String topicLogKey = EventKeys.topicLogKey(topicLogMetadata.topicId());
            if (queueRepairIfLocalServerIsResponsible(ReplicaRecordType.TOPIC_LOG, topicLogKey,
                    topicLogMetadata.topicId(), membershipSnapshotVersion, sourceServer.serverId(), availableServers))
                repairCandidates++;
        }
        return repairCandidates;
    }

    /**
     * Queues a peer-advertised record only when its topic is active and this
     * server belongs to its current responsible set.
     *
     * @return whether the record was a repair candidate
     */
    private boolean queueRepairIfLocalServerIsResponsible(ReplicaRecordType recordType, String recordKey,
            String topicId, String membershipSnapshotVersion, String sourceServerId,
            List<ReplicationServerEndpoint> availableServers) {
        if (findResponsibleServersForActiveTopic(recordKey, topicId, availableServers)
                .filter(this::includesLocalServer).isEmpty())
            return false;

        queueRepair(recordType, recordKey, topicId, membershipSnapshotVersion, List.of(sourceServerId));
        return true;
    }

    /**
     * Probes peers responsible for records held here. Successful probes clear
     * suspected and confirmed-down state; continued probes of relevant down
     * peers allow their recovery to be detected.
     */
    private void probeRelevantPeers(List<ReplicationServerEndpoint> activeServers) {
        Set<String> relevantPeerIds = relevantPeerServerIds(activeServers);
        for (ReplicationServerEndpoint peer : activeServers) {
            if (peer.serverId().equals(localServer.serverId()) || !relevantPeerIds.contains(peer.serverId()))
                continue;
            PeerFailureDetector.Transition transition = httpClient.probe(peer, probeTimeout)
                    ? failureDetector.succeeded(peer.serverId())
                    : failureDetector.failed(peer.serverId());
            switch (transition) {
                case SUSPECTED -> LOG.info("REPLICATION_PEER_SUSPECTED serverId={} peerServerId={}",
                        localServer.serverId(), peer.serverId());
                case CONFIRMED_DOWN -> {
                    LOG.info("REPLICATION_PEER_CONFIRMED_DOWN serverId={} peerServerId={}",
                            localServer.serverId(), peer.serverId());
                }
                case RECOVERED -> {
                    LOG.info("REPLICATION_PEER_RECOVERED serverId={} peerServerId={}",
                            localServer.serverId(), peer.serverId());
                }
                case NONE -> {
                }
            }
        }
    }

    /**
     * Finds the union of owners for local events and topic logs under registry
     * membership. This includes peers currently marked down so they can be
     * probed again; this server itself is removed from the result.
     */
    private Set<String> relevantPeerServerIds(List<ReplicationServerEndpoint> activeServers) {
        Set<String> relevantPeerIds = new LinkedHashSet<>();
        for (StoredEvent record : eventStore.eventRecords())
            findResponsibleServersForActiveTopic(record.eventKey(), record.eventEnvelope().topicId(), activeServers)
                    .ifPresent(servers -> relevantPeerIds.addAll(sortedServerIds(servers)));
        for (String topicId : eventStore.topicIds())
            findResponsibleServersForActiveTopic(EventKeys.topicLogKey(topicId), topicId, activeServers)
                    .ifPresent(servers -> relevantPeerIds.addAll(sortedServerIds(servers)));
        relevantPeerIds.remove(localServer.serverId());
        return relevantPeerIds;
    }

    /**
     * Rechecks every local record against current placement, excluding peers
     * confirmed down. The under-replicated set is rebuilt on each pass;
     * peer inventories are cached for this pass to avoid repeated requests.
     */
    private void reconcileLocalRecords(List<ReplicationServerEndpoint> activeServers, String membershipSnapshotVersion,
            Map<String, ReplicaInventory> inventories) {
        underReplicated.clear();
        List<ReplicationServerEndpoint> availableServers = serversNotConfirmedDown(activeServers);
        for (StoredEvent record : eventStore.eventRecords())
            findResponsibleServersForActiveTopic(record.eventKey(), record.eventEnvelope().topicId(), availableServers)
                    .ifPresent(responsibleServers -> reconcileLocalReplica(ReplicaRecordType.EVENT, record.eventKey(),
                            record.eventEnvelope().topicId(), responsibleServers, membershipSnapshotVersion, inventories));
        for (String topicId : eventStore.topicIds()) {
            String topicLogKey = EventKeys.topicLogKey(topicId);
            findResponsibleServersForActiveTopic(topicLogKey, topicId, availableServers)
                    .ifPresent(responsibleServers -> reconcileLocalReplica(ReplicaRecordType.TOPIC_LOG, topicLogKey,
                            topicId, responsibleServers, membershipSnapshotVersion, inventories));
        }
    }

    /**
     * Checks current owners' inventories for a local event or topic log.
     * An event is present when its key appears in the inventory; a topic log
     * is current when every local publisher has sufficient progress there.
     * Missing copies trigger repair requests with this server and confirmed
     * holders as sources. If this server is no longer an owner, it releases its
     * copy only after all new owners confirm theirs under current membership.
     */
    private void reconcileLocalReplica(ReplicaRecordType recordType, String recordKey, String topicId,
            List<ReplicationServerEndpoint> responsibleServers, String membershipSnapshotVersion,
            Map<String, ReplicaInventory> inventories) {
        List<ReplicationServerEndpoint> sourceServers = new ArrayList<>();
        sourceServers.add(localServer);
        int replicaHolders = includesLocalServer(responsibleServers) ? 1 : 0;
        for (ReplicationServerEndpoint target : responsibleServers) {
            if (target.serverId().equals(localServer.serverId()))
                continue;
            Optional<ReplicaInventory> targetInventory = fetchPeerInventory(target, inventories);
            boolean present = targetInventory.filter(value -> value.membershipVersion().equals(membershipSnapshotVersion))
                    .map(value -> switch (recordType) {
                        case EVENT -> inventoryContainsEventKey(value, recordKey);
                        case TOPIC_LOG -> inventoryCoversTopicLog(value, topicId, eventStore.publisherProgress(topicId));
                    }).orElse(false);
            if (present) {
                replicaHolders++;
                sourceServers.add(target);
            } else {
                sendRepairRequest(target, recordType, recordKey, topicId, sourceServers);
            }
        }
        if (replicaHolders < responsibleServers.size())
            recordUnderReplicated(recordType, recordKey, responsibleServers, replicaHolders);
        if (!includesLocalServer(responsibleServers) && !responsibleServers.isEmpty()
                && replicaHolders == responsibleServers.size()
                && membershipSnapshotVersion.equals(ReplicationMembershipProvider.versionOf(
                        sortServersById(replicationMembershipProvider.activeServers())))) {
            boolean released = switch (recordType) {
                case EVENT -> eventStore.deleteEvent(recordKey);
                case TOPIC_LOG -> eventStore.deleteTopicLog(topicId);
            };
            if (released)
                LOG.info(
                        "REPLICA_RELEASED serverId={} eventKey={} topicId={} oldReplicaSet={} newReplicaSet={} reason=placement_changed",
                        localServer.serverId(), recordKey, topicId, localServer.serverId(), sortedServerIds(responsibleServers));
        }
    }

    /** Notifies a missing owner of known fetch sources and locally confirmed failures. */
    private void sendRepairRequest(ReplicationServerEndpoint targetServer, ReplicaRecordType recordType,
            String recordKey, String topicId, List<ReplicationServerEndpoint> sourceServers) {
        try {
            httpClient.requestRepair(targetServer, new ReplicaRepairRequest(recordType, recordKey, topicId,
                    sortedServerIds(sourceServers),
                    new ArrayList<>(failureDetector.confirmedDown())));
        } catch (RuntimeException ex) {
            LOG.debug("Repair notification failed peerServerId={} eventKey={} reason={}",
                    targetServer.serverId(), recordKey, errorMessage(ex));
        }
    }

    /** Records a deficit until the next reconciliation rebuilds the status set. */
    private void recordUnderReplicated(ReplicaRecordType recordType, String recordKey,
            List<ReplicationServerEndpoint> responsibleServers, int replicaHolders) {
        underReplicated.add(recordType + ":" + recordKey);
        LOG.info(
                "REPLICA_UNDER_REPLICATED serverId={} eventKey={} oldReplicaSet={} newReplicaSet={} replicaCount={} desiredReplicaCount={}",
                localServer.serverId(), recordKey, localServer.serverId(), sortedServerIds(responsibleServers), replicaHolders, responsibleServers.size());
    }

    /**
     * Rechecks each queued record's placement, then fetches it from a surviving
     * source. A failed fetch remains queued for retry; a record no longer
     * assigned here is removed from the queue.
     */
    private void processQueuedRepairs(List<ReplicationServerEndpoint> activeServers, String membershipSnapshotVersion) {
        List<ReplicationServerEndpoint> availableServers = serversNotConfirmedDown(activeServers);
        for (RepairTask task : new ArrayList<>(pendingRepairs.values())) {
            Optional<List<ReplicationServerEndpoint>> placement = findResponsibleServersForActiveTopic(
                    task.recordKey, task.topicId, availableServers);
            if (placement.isEmpty())
                continue;
            List<ReplicationServerEndpoint> responsibleServers = placement.orElseThrow();
            if (!includesLocalServer(responsibleServers)) {
                pendingRepairs.remove(task.recordId());
                continue;
            }
            LOG.info("REPLICA_REPAIR_STARTED serverId={} eventKey={} topicId={} oldReplicaSet={} newReplicaSet={}",
                    localServer.serverId(), task.recordKey, task.topicId, task.sortedSourceServerIds(), sortedServerIds(responsibleServers));
            task.attempts++;
            try {
                fetchAndStoreReplica(task, activeServers);
                pendingRepairs.remove(task.recordId());
                LOG.info("REPLICA_REPAIR_COMPLETED serverId={} eventKey={} topicId={} membershipVersion={}",
                        localServer.serverId(), task.recordKey, task.topicId, membershipSnapshotVersion);
            } catch (RuntimeException ex) {
                task.lastError = errorMessage(ex);
                LOG.info("REPLICA_REPAIR_FAILED serverId={} eventKey={} topicId={} reason={}",
                        localServer.serverId(), task.recordKey, task.topicId, task.lastError);
            }
        }
    }

    /**
     * Tries known sources until an event or topic-log update is stored. Events
     * must match the requested key and topic before reaching the replication
     * service; topic logs transfer publisher progress rather than event data.
     */
    private void fetchAndStoreReplica(RepairTask task, List<ReplicationServerEndpoint> activeServers) {
        RuntimeException lastFailure = null;
        for (String sourceServerId : task.sourceServerIds) {
            if (sourceServerId.equals(localServer.serverId())
                    || failureDetector.confirmedDown().contains(sourceServerId))
                continue;
            Optional<ReplicationServerEndpoint> matchingSourceServer = activeServers.stream()
                    .filter(server -> server.serverId().equals(sourceServerId)).findFirst();
            if (matchingSourceServer.isEmpty())
                continue;
            ReplicationServerEndpoint sourceServer = matchingSourceServer.orElseThrow();
            try {
                if (task.recordType == ReplicaRecordType.EVENT) {
                    StoredEvent event = httpClient.lookupLocal(sourceServer, task.recordKey)
                            .orElseThrow(() -> new IllegalStateException("surviving event replica is missing"));
                    if (!task.recordKey.equals(event.eventKey())
                            || !task.topicId.equals(event.eventEnvelope().topicId()))
                        throw new IllegalStateException("surviving replica does not match repair request");
                    LOG.info("REPLICA_REPAIR_FETCHED serverId={} peerServerId={} eventKey={} topicId={}",
                            localServer.serverId(), sourceServer.serverId(), task.recordKey, task.topicId);
                    replicationService.storeReplica(event);
                } else {
                    List<PublisherProgress> progress = httpClient.publisherProgress(sourceServer, task.topicId, true);
                    if (progress.isEmpty())
                        throw new IllegalStateException("surviving topic log replica is missing");
                    LOG.info("REPLICA_REPAIR_FETCHED serverId={} peerServerId={} eventKey={} topicId={}",
                            localServer.serverId(), sourceServer.serverId(), task.recordKey, task.topicId);
                    progress.forEach(value -> replicationService.storeProgressReplica(task.topicId, value));
                }
                LOG.info("REPLICA_REPAIR_STORED serverId={} eventKey={} topicId={}",
                        localServer.serverId(), task.recordKey, task.topicId);
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure == null ? new IllegalStateException("no surviving replica is available") : lastFailure;
    }

    /** Deduplicates repairs by record type and key while accumulating fetch sources. */
    private void queueRepair(ReplicaRecordType recordType, String recordKey, String topicId,
            String membershipSnapshotVersion, Collection<String> sourceServerIds) {
        String recordId = recordType + ":" + recordKey;
        RepairTask task = pendingRepairs.computeIfAbsent(recordId,
                ignored -> new RepairTask(recordType, recordKey, topicId, membershipSnapshotVersion));
        task.sourceServerIds.addAll(sourceServerIds);
    }

    /** Caches both successful and failed inventory lookups for one maintenance pass. */
    private Optional<ReplicaInventory> fetchPeerInventory(ReplicationServerEndpoint peer,
            Map<String, ReplicaInventory> inventoriesByServerId) {
        if (inventoriesByServerId.containsKey(peer.serverId()))
            return Optional.ofNullable(inventoriesByServerId.get(peer.serverId()));
        try {
            ReplicaInventory peerInventory = httpClient.inventory(peer);
            inventoriesByServerId.put(peer.serverId(), peerInventory);
            return Optional.of(peerInventory);
        } catch (RuntimeException ex) {
            inventoriesByServerId.put(peer.serverId(), null);
            return Optional.empty();
        }
    }

    /**
     * Uses the last topic snapshot, falling back to a direct registry lookup
     * when a topic is absent or inactive. An unavailable or inactive topic
     * cannot be assigned for repair in this pass.
     */
    private Optional<TopicState> findActiveTopicState(String topicId) {
        TopicState cached = topics.get(topicId);
        if (cached != null && cached.active())
            return Optional.of(cached);
        try {
            TopicState loaded = replicationService.topicState(topicId);
            topics.put(topicId, loaded);
            return Optional.of(loaded);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Applies the active topic's replication factor to the supplied servers.
     * Callers pass either all registry members for probing or the available
     * members for repair placement.
     */
    private Optional<List<ReplicationServerEndpoint>> findResponsibleServersForActiveTopic(String recordKey,
            String topicId, List<ReplicationServerEndpoint> candidateServers) {
        return findActiveTopicState(topicId).map(topic -> DhtAssignment.responsibleServers(recordKey,
                candidateServers, topic.replicationFactor()));
    }

    /** Excludes peers that have crossed the local failure threshold. */
    private List<ReplicationServerEndpoint> serversNotConfirmedDown(List<ReplicationServerEndpoint> activeServers) {
        Set<String> down = failureDetector.confirmedDown();
        return activeServers.stream().filter(server -> !down.contains(server.serverId())).toList();
    }

    private boolean includesLocalServer(List<ReplicationServerEndpoint> servers) {
        return servers.stream().anyMatch(server -> server.serverId().equals(localServer.serverId()));
    }

    private static boolean inventoryContainsEventKey(ReplicaInventory inventory, String key) {
        return inventory.events().stream().anyMatch(value -> value.eventKey().equals(key));
    }

    /**
     * A peer's topic log covers the local log only if each local publisher has
     * a higher sequence number there, or the same sequence number with a
     * timestamp at least as high.
     */
    private static boolean inventoryCoversTopicLog(ReplicaInventory inventory, String topicId,
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

    /** Sorts membership by server ID so peer processing follows a stable order across snapshots. */
    private static List<ReplicationServerEndpoint> sortServersById(List<ReplicationServerEndpoint> activeServers) {
        return activeServers.stream().sorted(Comparator.comparing(ReplicationServerEndpoint::serverId)).toList();
    }

    private static Set<String> serverIdSet(List<ReplicationServerEndpoint> servers) {
        return new TreeSet<>(sortedServerIds(servers));
    }

    private static List<String> sortedServerIds(Collection<ReplicationServerEndpoint> servers) {
        return servers.stream().map(ReplicationServerEndpoint::serverId).sorted().toList();
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

}
