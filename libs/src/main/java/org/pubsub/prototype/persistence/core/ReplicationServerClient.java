package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.registry.replication.ReplicationMembershipProvider;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.pubsub.prototype.constants.PersistenceConstants.SINCE_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;

/**
 * Sends a Pub/Sub node's storage and recovery requests to replication servers.
 * It gets server addresses from {@link ReplicationMembershipProvider} and makes HTTP
 * requests through {@link ReplicationHttpClient}. For storage, it submits an
 * {@link EventEnvelope} to one server; that server chooses where copies are kept.
 * For recovery, it finds stored events and reads the latest sequence reported
 * by each publisher. This client owns the supplied {@link ReplicationHttpClient}
 * and closes it when this client is closed.
 */
public final class ReplicationServerClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationServerClient.class);
    private final ReplicationMembershipProvider membership;
    private final ReplicationHttpClient httpClient;
    private final AtomicInteger nextServerIndex = new AtomicInteger();

    public ReplicationServerClient(ReplicationMembershipProvider membership, ReplicationHttpClient httpClient) {
        this.membership = membership;
        this.httpClient = httpClient;
    }

    /**
     * Submits the event to an active server for storage. Tries another server
     * if the request fails; throws if no server accepts it.
     */
    public StoredEvent submitEventForStorage(EventEnvelope envelope) {
        List<ReplicationServerEndpoint> servers = membership.activeServers();
        if (servers.isEmpty())
            throw new IllegalStateException("no active replication servers");
        RuntimeException last = null;
        int start = Math.floorMod(nextServerIndex.getAndIncrement(), servers.size());
        for (int i = 0; i < servers.size(); i++) {
            ReplicationServerEndpoint server = servers.get((start + i) % servers.size());
            long startedAt = System.nanoTime();
            try {
                StoredEvent stored = httpClient.store(server, envelope);
                LOG.info("PERSIST_ENTRY_ATTEMPT eventId={} sequenceNumber={} serverId={} attempt={} durationMs={} result=stored",
                        envelope.eventId(), envelope.sequenceNumber(), server.serverId(), i + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return stored;
            } catch (RuntimeException ex) {
                LOG.warn("PERSIST_ENTRY_ATTEMPT eventId={} sequenceNumber={} serverId={} attempt={} durationMs={} result=failed reason={}",
                        envelope.eventId(), envelope.sequenceNumber(), server.serverId(), i + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), ex.toString());
                last = ex;
            }
        }
        throw new IllegalStateException("all replication entry servers failed", last);
    }

    /**
     * Finds a stored event through an active server. Tries another server only
     * if the request fails. Returns empty if the event is absent or no server
     * responds.
     */
    public Optional<StoredEvent> findStoredEvent(String topicId, String eventKey) {
        List<ReplicationServerEndpoint> servers = membership.activeServers();
        if (servers.isEmpty())
            return Optional.empty();
        int start = Math.floorMod(nextServerIndex.getAndIncrement(), servers.size());
        for (int i = 0; i < servers.size(); i++) {
            try {
                return httpClient.lookup(servers.get((start + i) % servers.size()), topicId, eventKey);
            } catch (RuntimeException ignored) {
                // Try another entry server if this one is unavailable.
            }
        }
        return Optional.empty();
    }

    /**
     * Fetches each publisher's latest stored sequence and timestamp from all
     * active servers. The timestamp argument limits which publishers are returned.
     * Keeps the highest sequence per publisher, using the latest timestamp to
     * break ties. Failed server requests are skipped.
     */
    public List<PublisherProgress> fetchLatestSequencesByPublisher(String topicId, long sinceTimestamp) {
        requireNonNegative(sinceTimestamp, SINCE_TIMESTAMP_FIELD);
        Map<String, PublisherProgress> merged = new LinkedHashMap<>();
        int successful = 0;
        for (ReplicationServerEndpoint server : membership.activeServers()) {
            try {
                for (PublisherProgress progress : httpClient.publisherProgress(server, topicId, false, sinceTimestamp)) {
                    if (!topicId.equals(progress.topicId()))
                        continue;
                    merged.merge(progress.publisherKeyId(), progress,
                            (left, right) -> left.latestSequenceNumber() > right.latestSequenceNumber()
                                    || (left.latestSequenceNumber() == right.latestSequenceNumber()
                                            && left.latestTimestamp() >= right.latestTimestamp()) ? left : right);
                }
                successful++;
            } catch (RuntimeException ignored) {
                // Try another entry server.
            }
        }
        if (successful == 0) throw new IllegalStateException("No replication server returned publisher progress");
        return merged.values().stream().sorted(Comparator.comparing(PublisherProgress::publisherKeyId)).toList();
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
