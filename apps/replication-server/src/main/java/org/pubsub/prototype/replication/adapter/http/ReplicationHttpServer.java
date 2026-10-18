package org.pubsub.prototype.replication.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiFields;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.ApiValues;
import org.pubsub.prototype.http.HttpErrorCodes;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairAuth;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.replication.service.ReplicaMaintenanceManager;
import org.pubsub.prototype.replication.service.ReplicationService;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.files.JsonSupport;
import org.pubsub.prototype.util.validation.Validators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP routes for persistence, replication, and maintenance. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public final class ReplicationHttpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationHttpServer.class);

    /** Maximum JSON request body accepted by this server (4 MiB). */
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ReplicationService service;
    private final ReplicationMembership membership;
    private final ReplicationServer self;
    private final ReplicaMaintenanceManager maintenance;
    private final Map<String, Long> seenRepairNonces = new ConcurrentHashMap<>();

    public ReplicationHttpServer(String host, int port, ReplicationServer self, ReplicationService service,
                                 ReplicationMembership membership, ReplicaMaintenanceManager maintenance,
                                 int workerThreads) throws IOException {
        this.self = self;
        this.service = service;
        this.membership = membership;
        this.maintenance = maintenance;
        ResourceConfig routes = new ResourceConfig()
                .register(this)
                .register(JacksonFeature.class)
                .register(new JsonMapperProvider())
                .register(new JsonErrorMapper());
        this.server = JdkHttpServerFactory.createHttpServer(
                URI.create("http://" + host + ":" + port + "/"), routes, false);
        this.executor = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "replication-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    // Replication server routes

    @POST
    @Path(ApiPaths.EVENTS)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response persist(InputStream body) throws IOException {
        try {
            return json(201, service.persist(read(body, EventEnvelope.class)));
        } catch (IllegalArgumentException ex) {
            LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), JsonHttp.safeMessage(ex));
            return error(400, JsonHttp.safeMessage(ex));
        } catch (RuntimeException ex) {
            LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), JsonHttp.safeMessage(ex));
            return error(503, JsonHttp.safeMessage(ex));
        }
    }

    @GET
    @Path(ApiPaths.EVENTS + "/{eventKey}")
    public Response event(@PathParam("eventKey") String eventKey,
                          @QueryParam(ApiParameters.TOPIC_ID) String topicId) {
        String key = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
        String topic = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
        return service.lookup(topic, key).map(found -> json(200, found))
                .orElseGet(() -> json(404, Map.of(HttpErrorCodes.ERROR, "event_not_found", ApiFields.EVENT_KEY, key)));
    }

    @POST
    @Path(ApiPaths.REPLICA_EVENTS)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response storeReplica(InputStream body) throws IOException {
        StoredEvent event = read(body, StoredEvent.class);
        service.storeReplica(event);
        return json(201, event);
    }

    @GET
    @Path(ApiPaths.REPLICA_EVENTS + "/{eventKey}")
    public Response replicaEvent(@PathParam("eventKey") String eventKey) {
        String key = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
        return service.lookupLocal(key).map(found -> json(200, found))
                .orElseGet(() -> json(404, Map.of(HttpErrorCodes.ERROR, "event_not_found", ApiFields.EVENT_KEY, key)));
    }

    @GET
    @Path(ApiPaths.TOPICS + "/{topicId}/publishers")
    public Response publishers(@PathParam("topicId") String topicId,
                               @QueryParam(ApiParameters.SINCE_TIMESTAMP) String sinceTimestamp) {
        long since = sinceTimestamp == null ? 0 :
                Validators.requireNonNegative(Long.parseLong(sinceTimestamp), ApiParameters.SINCE_TIMESTAMP);
        return json(200, service.publisherProgress(
                HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD), since));
    }

    @GET
    @Path(ApiPaths.REPLICA_TOPICS + "/{topicId}/publishers")
    public Response replicaPublishers(@PathParam("topicId") String topicId) {
        return json(200, service.localPublisherProgress(
                HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD)));
    }

    @POST
    @Path(ApiPaths.REPLICA_TOPICS + "/{topicId}/publishers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response storeReplicaPublisher(@PathParam("topicId") String topicId, InputStream body) throws IOException {
        String key = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
        PublisherProgress progress = read(body, PublisherProgress.class);
        service.storeProgressReplica(key, progress);
        return json(200, progress);
    }

    @GET
    @Path(ApiPaths.HEALTH)
    public Response health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ApiFields.STATUS, ApiValues.UP);
        result.put(ApiFields.SERVER_ID, self.serverId());
        result.put(ApiFields.MEMBERSHIP, membership.activeServers());
        result.put(ApiFields.EVENT_KEYS, maintenance.localEventKeys());
        return json(200, result);
    }

    @POST
    @Path(ApiPaths.REPLICA_REPAIR)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response repair(@HeaderParam(ReplicaRepairAuth.SERVER_ID_HEADER) String senderId,
                           @HeaderParam(ReplicaRepairAuth.PUBLIC_KEY_HEADER) String publicKey,
                           @HeaderParam(ReplicaRepairAuth.TIMESTAMP_HEADER) String timestamp,
                           @HeaderParam(ReplicaRepairAuth.NONCE_HEADER) String nonce,
                           @HeaderParam(ReplicaRepairAuth.SIGNATURE_HEADER) String signature,
                           InputStream body) throws IOException {
        ReplicaRepairRequest request = read(body, ReplicaRepairRequest.class);
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (RuntimeException ex) {
            return error(403, "invalid repair sender");
        }
        var signed = new ReplicaRepairAuth.SignedRequest(senderId, publicKey, signedAt, nonce, signature);
        Set<String> activeIds = membership.activeServers().stream().map(ReplicationServer::serverId)
                .collect(java.util.stream.Collectors.toSet());
        if (!ReplicaRepairAuth.verify(request, signed, activeIds, self.serverId(), Clock.systemUTC()))
            return error(403, "invalid repair sender");
        long now = Clock.systemUTC().millis();
        seenRepairNonces.entrySet().removeIf(entry -> entry.getValue() < now);
        if (seenRepairNonces.putIfAbsent(senderId + ":" + nonce,
                signedAt + ReplicaRepairAuth.MAX_CLOCK_SKEW_MS) != null)
            return error(403, "replayed repair request");
        maintenance.requestRepair(request);
        return json(202, request);
    }

    @GET
    @Path(ApiPaths.MAINTENANCE_STATUS)
    public Response maintenanceStatus() {
        return json(200, maintenance.status());
    }

    @GET
    @Path(ApiPaths.MAINTENANCE_REPLICAS)
    public Response maintenanceReplicas() {
        return json(200, maintenance.inventory());
    }

    private static <T> T read(InputStream body, Class<T> type) throws IOException {
        byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES + 1);
        Validators.require(bytes.length <= MAX_REQUEST_BYTES, "request payload too large");
        return JsonSupport.MAPPER.readValue(bytes, type);
    }

    private static Response json(int status, Object body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Response error(int status, String message) {
        return json(status, Map.of(HttpErrorCodes.ERROR, message));
    }

    @Provider
    private static final class JsonMapperProvider implements ContextResolver<ObjectMapper> {
        @Override
        public ObjectMapper getContext(Class<?> type) {
            return JsonSupport.MAPPER;
        }
    }

    @Provider
    private static final class JsonErrorMapper implements ExceptionMapper<Exception> {
        @Override
        public Response toResponse(Exception exception) {
            if (exception instanceof WebApplicationException web) {
                int status = web.getResponse().getStatus();
                if (status == 404) return error(status, HttpErrorCodes.NOT_FOUND);
                if (status == 405) return error(status, HttpErrorCodes.METHOD_NOT_ALLOWED);
                return error(status, JsonHttp.safeMessage(exception));
            }
            if (exception instanceof IllegalArgumentException || exception instanceof IOException) {
                return error(400, JsonHttp.safeMessage(exception));
            }
            return error(500, JsonHttp.safeMessage(exception));
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
