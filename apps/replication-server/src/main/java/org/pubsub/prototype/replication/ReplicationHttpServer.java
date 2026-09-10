package org.pubsub.prototype.replication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.HttpErrorCodes;
import org.pubsub.prototype.http.HttpMethods;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.core.PersistenceJson;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class ReplicationHttpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationHttpServer.class);
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private final HttpServer server;
    private final ReplicationService service;
    private final ReplicationMembership membership;
    private final ReplicationServer self;
    private final ReplicaMaintenanceManager maintenance;

    ReplicationHttpServer(String host, int port, ReplicationServer self, ReplicationService service,
                          ReplicationMembership membership, ReplicaMaintenanceManager maintenance) throws IOException {
        this.self = self;
        this.service = service;
        this.membership = membership;
        this.maintenance = maintenance;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext(ApiPaths.EVENTS, this::events);
        server.createContext(ApiPaths.TOPICS, this::topics);
        server.createContext(ApiPaths.REPLICA_EVENTS, this::replicaEvents);
        server.createContext(ApiPaths.REPLICA_TOPICS, this::replicaTopics);
        server.createContext(ApiPaths.REPLICA_REPAIR, this::replicaRepair);
        server.createContext(ApiPaths.MAINTENANCE_STATUS, this::maintenanceStatus);
        server.createContext(ApiPaths.MAINTENANCE_REPLICAS, this::maintenanceReplicas);
        server.createContext(ApiPaths.HEALTH, this::health);
        server.setExecutor(Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "replication-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void start() {
        server.start();
    }

    private void events(HttpExchange exchange) throws IOException {
        String suffix = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.EVENTS);
        try {
            if (HttpMethods.POST.equals(exchange.getRequestMethod()) && suffix.isEmpty()) {
                StoredEvent event = service.persist(read(exchange, EventEnvelope.class));
                respond(exchange, 201, event);
            } else if (HttpMethods.GET.equals(exchange.getRequestMethod()) && !suffix.isEmpty()) {
                String key = PersistenceHex.require256(suffix, "eventKey");
                var found = service.lookup(key);
                if (found.isPresent()) respond(exchange, 200, found.orElseThrow());
                else respond(exchange, 404, Map.of(HttpErrorCodes.ERROR, "event_not_found", "eventKey", key));
            } else {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            }
        } catch (IllegalArgumentException ex) {
            if (HttpMethods.POST.equals(exchange.getRequestMethod())) {
                LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), JsonHttp.safeMessage(ex));
            }
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        } catch (RuntimeException ex) {
            LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), JsonHttp.safeMessage(ex));
            respond(exchange, 503, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        }
    }

    private void replicaEvents(HttpExchange exchange) throws IOException {
        String suffix = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.REPLICA_EVENTS);
        try {
            if (HttpMethods.POST.equals(exchange.getRequestMethod()) && suffix.isEmpty()) {
                StoredEvent event = read(exchange, StoredEvent.class);
                service.storeReplica(event);
                respond(exchange, 201, event);
            } else if (HttpMethods.GET.equals(exchange.getRequestMethod()) && !suffix.isEmpty()) {
                String key = PersistenceHex.require256(suffix, "eventKey");
                var found = service.lookupLocal(key);
                if (found.isPresent()) respond(exchange, 200, found.orElseThrow());
                else respond(exchange, 404, Map.of(HttpErrorCodes.ERROR, "event_not_found", "eventKey", key));
            } else respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        }
    }

    private void topics(HttpExchange exchange) throws IOException {
        String[] parts = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.TOPICS).split("/");
        try {
            if (!HttpMethods.GET.equals(exchange.getRequestMethod()) || parts.length != 2
                    || !ApiPaths.PUBLISHERS_SEGMENT.equals(parts[1])) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            long sinceTimestamp = JsonHttp.queryLong(exchange.getRequestURI(), ApiParameters.SINCE_TIMESTAMP, 0);
            respond(exchange, 200, service.publisherProgress(
                    PersistenceHex.require256(parts[0], "topicId"), sinceTimestamp));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        }
    }

    private void replicaTopics(HttpExchange exchange) throws IOException {
        String[] parts = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.REPLICA_TOPICS).split("/");
        try {
            if (parts.length != 2 || !ApiPaths.PUBLISHERS_SEGMENT.equals(parts[1])) {
                respond(exchange, 404, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.NOT_FOUND));
                return;
            }
            String topicId = PersistenceHex.require256(parts[0], "topicId");
            if (HttpMethods.GET.equals(exchange.getRequestMethod())) {
                respond(exchange, 200, service.localPublisherProgress(topicId));
            } else if (HttpMethods.POST.equals(exchange.getRequestMethod())) {
                PublisherProgress progress = read(exchange, PublisherProgress.class);
                service.storeProgressReplica(topicId, progress);
                respond(exchange, 200, progress);
            } else respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("serverId", self.serverId());
        result.put("membership", membership.activeServers());
        result.put("eventKeys", service.store().eventKeys());
        respond(exchange, 200, result);
    }

    private void replicaRepair(HttpExchange exchange) throws IOException {
        try {
            if (!HttpMethods.POST.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            ReplicaRepairRequest request = read(exchange, ReplicaRepairRequest.class);
            maintenance.requestRepair(request);
            respond(exchange, 202, request);
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
        }
    }

    private void maintenanceStatus(HttpExchange exchange) throws IOException {
        if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        respond(exchange, 200, maintenance.status());
    }

    private void maintenanceReplicas(HttpExchange exchange) throws IOException {
        if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        respond(exchange, 200, maintenance.inventory());
    }

    private static <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        return JsonHttp.readBounded(exchange, PersistenceJson.MAPPER, type, MAX_REQUEST_BYTES);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        JsonHttp.respond(exchange, PersistenceJson.MAPPER, status, body);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
