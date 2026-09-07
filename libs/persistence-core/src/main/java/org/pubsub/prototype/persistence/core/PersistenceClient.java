package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistenceClient {
    private final ReplicationMembership membership;
    private final ReplicationHttpClient http;
    private final AtomicInteger next = new AtomicInteger();

    public PersistenceClient(ReplicationMembership membership, ReplicationHttpClient http) {
        this.membership = membership;
        this.http = http;
    }

    public StoredEvent store(EventEnvelope envelope) {
        List<ReplicationServer> servers = membership.activeServers();
        if (servers.isEmpty()) throw new IllegalStateException("no active replication servers");
        RuntimeException last = null;
        int start = Math.floorMod(next.getAndIncrement(), servers.size());
        for (int i = 0; i < servers.size(); i++) {
            try {
                return http.store(servers.get((start + i) % servers.size()), envelope);
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("all replication entry servers failed", last);
    }

    public Optional<StoredEvent> lookup(String eventKey) {
        for (ReplicationServer server : membership.activeServers()) {
            try {
                Optional<StoredEvent> result = http.lookup(server, eventKey, false);
                if (result.isPresent()) return result;
            } catch (RuntimeException ignored) {
                // Try another entry server.
            }
        }
        return Optional.empty();
    }

    public List<PublisherProgress> publisherProgress(String topicId) {
        return publisherProgress(topicId, 0);
    }

    public List<PublisherProgress> publisherProgress(String topicId, long sinceTimestamp) {
        if (sinceTimestamp < 0) throw new IllegalArgumentException("sinceTimestamp must be non-negative");
        Map<String, PublisherProgress> merged = new LinkedHashMap<>();
        for (ReplicationServer server : membership.activeServers()) {
            try {
                for (PublisherProgress progress : http.publisherProgress(server, topicId, false, sinceTimestamp)) {
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
}
