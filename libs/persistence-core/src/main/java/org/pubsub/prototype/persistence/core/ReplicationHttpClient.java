package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.HttpMethods;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicaInventory;
import org.pubsub.prototype.persistence.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class ReplicationHttpClient implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int retries;

    public ReplicationHttpClient(Duration connectionTimeout, Duration requestTimeout, int retries) {
        if (retries < 0) throw new IllegalArgumentException("retries must be non-negative");
        this.client = HttpClient.newBuilder().connectTimeout(connectionTimeout).build();
        this.requestTimeout = requestTimeout;
        this.retries = retries;
    }

    public StoredEvent store(ReplicationServer server, EventEnvelope envelope) {
        return sendJson(server, ApiPaths.EVENTS, HttpMethods.POST, envelope, StoredEvent.class);
    }

    public void storeReplica(ReplicationServer server, StoredEvent event) {
        sendJson(server, ApiPaths.REPLICA_EVENTS, HttpMethods.POST, event, StoredEvent.class);
    }

    public Optional<StoredEvent> lookup(ReplicationServer server, String eventKey, boolean direct) {
        String prefix = direct ? ApiPaths.REPLICA_EVENTS : ApiPaths.EVENTS;
        return getOptional(server, ApiPaths.child(prefix, eventKey), StoredEvent.class);
    }

    public List<PublisherProgress> publisherProgress(ReplicationServer server, String topicId, boolean direct) {
        return publisherProgress(server, topicId, direct, 0);
    }

    public List<PublisherProgress> publisherProgress(ReplicationServer server, String topicId, boolean direct,
                                                     long sinceTimestamp) {
        if (sinceTimestamp < 0) throw new IllegalArgumentException("sinceTimestamp must be non-negative");
        String prefix = direct ? ApiPaths.REPLICA_TOPICS : ApiPaths.TOPICS;
        try {
            String suffix = direct ? "" : "?" + ApiParameters.SINCE_TIMESTAMP + "=" + sinceTimestamp;
            HttpResponse<byte[]> response = send(request(server, ApiPaths.publishers(prefix, topicId) + suffix)
                    .GET().build());
            if (response.statusCode() == 404) return List.of();
            requireSuccess(response);
            return PersistenceJson.MAPPER.readValue(response.body(), PersistenceJson.MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, PublisherProgress.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid replication-server response", ex);
        }
    }

    public void storeProgressReplica(ReplicationServer server, String topicId, PublisherProgress progress) {
        sendJson(server, ApiPaths.publishers(ApiPaths.REPLICA_TOPICS, topicId), HttpMethods.POST, progress,
                PublisherProgress.class);
    }

    public boolean probe(ReplicationServer server, Duration timeout) {
        try {
            HttpResponse<byte[]> response = send(HttpRequest.newBuilder(server.uri(ApiPaths.HEALTH))
                    .timeout(timeout).GET().build(), 0);
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public ReplicaInventory inventory(ReplicationServer server) {
        return get(server, ApiPaths.MAINTENANCE_REPLICAS, ReplicaInventory.class);
    }

    public void requestRepair(ReplicationServer server, ReplicaRepairRequest request) {
        sendJson(server, ApiPaths.REPLICA_REPAIR, HttpMethods.POST, request, ReplicaRepairRequest.class);
    }

    private <T> Optional<T> getOptional(ReplicationServer server, String path, Class<T> type) {
        try {
            HttpResponse<byte[]> response = send(request(server, path).GET().build());
            if (response.statusCode() == 404) return Optional.empty();
            requireSuccess(response);
            return Optional.of(PersistenceJson.MAPPER.readValue(response.body(), type));
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid replication-server response", ex);
        }
    }

    private <T> T get(ReplicationServer server, String path, Class<T> type) {
        try {
            HttpResponse<byte[]> response = send(request(server, path).GET().build());
            requireSuccess(response);
            return PersistenceJson.MAPPER.readValue(response.body(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid replication-server response", ex);
        }
    }

    private <T> T sendJson(ReplicationServer server, String path, String method, Object body, Class<T> type) {
        try {
            byte[] json = PersistenceJson.MAPPER.writeValueAsBytes(body);
            HttpRequest request = request(server, path).header(JsonHttp.CONTENT_TYPE, JsonHttp.JSON_MEDIA_TYPE)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json)).build();
            HttpResponse<byte[]> response = send(request);
            requireSuccess(response);
            return PersistenceJson.MAPPER.readValue(response.body(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("Replication request failed", ex);
        }
    }

    private HttpRequest.Builder request(ReplicationServer server, String path) {
        return HttpRequest.newBuilder(server.uri(path)).timeout(requestTimeout);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        return send(request, retries);
    }

    private HttpResponse<byte[]> send(HttpRequest request, int retryCount) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalStateException("response payload too large");
                if (response.statusCode() >= 500 && attempt < retryCount) continue;
                return response;
            } catch (IOException ex) {
                last = new IllegalStateException("Replication server unavailable", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Replication request interrupted", ex);
            }
        }
        throw last == null ? new IllegalStateException("Replication request failed") : last;
    }

    private static void requireSuccess(HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Replication server returned " + response.statusCode() + ": "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public void close() {
        // JDK HttpClient owns no user-visible resource.
    }
}
