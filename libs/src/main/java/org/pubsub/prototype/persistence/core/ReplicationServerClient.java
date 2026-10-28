package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationServer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pubsub.prototype.constants.PersistenceConstants.SINCE_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;

/**
 * Sends a Pub/Sub node's storage and recovery requests to replication servers.
 * It gets server addresses from {@link ReplicationMembership} and makes HTTP
 * requests through {@link ReplicationHttpClient}. For storage, it submits an
 * {@link EventEnvelope} to one server; that server chooses where copies are kept.
 * For recovery, it finds stored events and reads the latest sequence reported
 * by each publisher. This client owns the supplied {@link ReplicationHttpClient}
 * and closes it when this client is closed.
 */
public final class ReplicationServerClient implements AutoCloseable {
    private final ReplicationMembership membership;
    private final ReplicationHttpClient httpClient;
    private final AtomicInteger nextServerIndex = new AtomicInteger();

    public ReplicationServerClient(ReplicationMembership membership, ReplicationHttpClient httpClient) {
        this.membership = membership;
        this.httpClient = httpClient;
    }

    /**
     * Submits the event to an active server for storage. Tries another server
     * if the request fails; throws if no server accepts it.
     */
    public StoredEvent submitEventForStorage(EventEnvelope envelope) {
        List<ReplicationServer> servers = membership.activeServers();
        if (servers.isEmpty())
            throw new IllegalStateException("no active replication servers");
        RuntimeException last = null;
        int start = Math.floorMod(nextServerIndex.getAndIncrement(), servers.size());
        for (int i = 0; i < servers.size(); i++) {
            try {
                return httpClient.store(servers.get((start + i) % servers.size()), envelope);
            } catch (RuntimeException ex) {
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
        List<ReplicationServer> servers = membership.activeServers();
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
        for (ReplicationServer server : membership.activeServers()) {
            try {
                for (PublisherProgress progress : httpClient.publisherProgress(server, topicId, false, sinceTimestamp)) {
                    if (!topicId.equals(progress.topicId()))
                        continue;
                    merged.merge(progress.publisherKeyId(), progress,
                            (left, right) -> left.latestSequenceNumber() > right.latestSequenceNumber()
                                    || (left.latestSequenceNumber() == right.latestSequenceNumber()
                                            && left.latestTimestamp() >= right.latestTimestamp()) ? left : right);
                }
            } catch (RuntimeException ignored) {
                // Try another entry server.
            }
        }
        return merged.values().stream().sorted(Comparator.comparing(PublisherProgress::publisherKeyId)).toList();
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
