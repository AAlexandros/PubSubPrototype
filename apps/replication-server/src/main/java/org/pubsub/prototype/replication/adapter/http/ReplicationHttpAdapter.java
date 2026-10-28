package org.pubsub.prototype.replication.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.HttpRequestAuthenticator;
import org.pubsub.prototype.auth.PublisherProgressAuthenticator;
import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiFields;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.ApiValues;
import org.pubsub.prototype.http.HttpErrorCodes;
import org.pubsub.prototype.http.HttpRequestBodyReader;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairRequest;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.replication.adapter.http.exception.PersistenceUnavailableException;
import org.pubsub.prototype.replication.service.ReplicaMaintenanceService;
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
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API for one replication server.
 *
 * <p>The API has three route groups:</p>
 * <ul>
 *   <li>{@code /v1/client/*} accepts publisher events and serves routed lookups.</li>
 *   <li>{@code /v1/replication/*} handles authenticated replica exchange and repair.</li>
 *   <li>{@code /v1/ops/*} exposes health and maintenance status.</li>
 * </ul>
 *
 * <p>Caller authentication for inter-server routes is handled by {@link ReplicaRequestFilter}. The public
 * event-persistence route authenticates the publisher by verifying the event signature and topic policy;
 * it does not authenticate the HTTP caller. Public lookups, health, and maintenance status are
 * unauthenticated; inter-server routes, including local replica reads, require server authentication.
 * JSON request bodies are limited to 4 MiB. Route details and response shapes are in
 * {@code documentation/replication-server-api.md}.</p>
 *
 * <p>{@link #start()} starts listening without blocking. Call {@link #close()} to stop the listener and its
 * worker threads.</p>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public final class ReplicationHttpAdapter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationHttpAdapter.class);

    /** Maximum JSON request body accepted by this server (4 MiB). */

    private final HttpServer server;
    private final ExecutorService executor;
    private final ReplicationService service;
    private final ReplicationMembership membership;
    private final ReplicationServer self;
    private final ReplicaMaintenanceService maintenance;
    private final Authenticator<EventEnvelope, TopicState> eventAuthenticator;
    private final Authenticator<PublisherProgressAuthenticator.Request, TopicState> progressAuthenticator;

    /**
     * Creates the HTTP API and its worker pool. This does not open the listening socket until {@link #start()}.
     *
     * @param host local interface to bind
     * @param port local TCP port to bind
     * @param self this server's registered identity and advertised endpoint
     * @param service persistence operations exposed through the API
     * @param membership current active replication-server membership
     * @param maintenance maintenance state and repair operations
     * @param eventAuthenticator verifies publisher events and topic authorization
     * @param progressAuthenticator verifies publisher progress and topic authorization
     * @param workerThreads number of concurrent HTTP request workers
     * @throws IOException if the HTTP listener cannot be created
     */
    public ReplicationHttpAdapter(String host, int port, ReplicationServer self, ReplicationService service,
                                 ReplicationMembership membership, ReplicaMaintenanceService maintenance,
                                 Authenticator<EventEnvelope, TopicState> eventAuthenticator,
                                 Authenticator<PublisherProgressAuthenticator.Request, TopicState> progressAuthenticator,
                                 int workerThreads) throws IOException {
        this.self = self;
        this.service = service;
        this.membership = membership;
        this.maintenance = maintenance;
        this.eventAuthenticator = Objects.requireNonNull(eventAuthenticator);
        this.progressAuthenticator = Objects.requireNonNull(progressAuthenticator);
        Authenticator<HttpRequestAuthenticator.Request, String> replicaAuthenticator = self == null || membership == null
                ? request -> { throw new SecurityException("replication authentication unavailable"); }
                : new HttpRequestAuthenticator(() -> membership.activeServers().stream()
                        .map(ReplicationServer::serverId).collect(java.util.stream.Collectors.toSet()),
                        self.serverId(), Clock.systemUTC());
        ResourceConfig routes = new ResourceConfig()
                .register(this)
                .register(new ReplicaRequestFilter(replicaAuthenticator))
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

    /** Starts accepting HTTP requests and returns immediately. */
    public void start() {
        server.start();
    }

    // Replication server routes

    /**
     * Accepts a publisher-signed event, validates the active topic and publisher, and routes the event
     * and signed publisher progress to their responsible replica sets.
     *
     * @param body JSON {@link EventEnvelope}
     * @return {@code 201} with the stored event record; {@code 400} for invalid event input or
     *         {@code 503} if persistence/routing fails
     */
    @POST
    @Path(ApiPaths.CLIENT_EVENTS)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response persist(InputStream body) throws IOException {
        EventEnvelope event = read(body, EventEnvelope.class);
        eventAuthenticator.authenticate(event);
        try {
            return json(201, service.persist(event));
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalArgumentException) throw ex;
            throw new PersistenceUnavailableException(ex);
        }
    }

    /**
     * Looks up an event by its SHA-256 event key, routing across responsible replicas and validating
     * the returned record before exposing it.
     *
     * @param eventKey 64-character hexadecimal event key
     * @param topicId required 64-character hexadecimal topic ID used to validate the result
     * @return {@code 200} with a {@link StoredEvent}, or {@code 404} when no valid record is found
     */
    @GET
    @Path(ApiPaths.CLIENT_EVENTS + "/{eventKey}")
    public Response event(@PathParam("eventKey") String eventKey,
                          @QueryParam(ApiParameters.TOPIC_ID) String topicId) {
        String key = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
        String topic = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
        return service.lookup(topic, key).map(found -> json(200, found))
                .orElseGet(() -> json(404, Map.of(HttpErrorCodes.ERROR, "event_not_found", ApiFields.EVENT_KEY, key)));
    }

    /**
     * Stores an event replica on this server. The request must be signed by an active replication server;
     * the event and its stored record are validated before writing.
     *
     * @param body JSON {@link StoredEvent}
     * @return {@code 201} with the stored record; authenticated callers receive {@code 403} on auth failure
     */
    @POST
    @Path(ApiPaths.REPLICATION_EVENTS)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response storeReplica(InputStream body) throws IOException {
        StoredEvent event = read(body, StoredEvent.class);
        eventAuthenticator.authenticate(event.eventEnvelope());
        service.storeReplica(event);
        return json(201, event);
    }

    /**
     * Reads an event from this server's local store without routing to other replicas.
     * This route requires a signed request from an active replication server.
     *
     * @param eventKey 64-character hexadecimal event key
     * @return {@code 200} with a local {@link StoredEvent}, or {@code 404} if this server has no copy
     */
    @GET
    @Path(ApiPaths.REPLICATION_EVENTS + "/{eventKey}")
    public Response replicaEvent(@PathParam("eventKey") String eventKey) {
        String key = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
        return service.lookupLocal(key).map(found -> json(200, found))
                .orElseGet(() -> json(404, Map.of(HttpErrorCodes.ERROR, "event_not_found", ApiFields.EVENT_KEY, key)));
    }

    /**
     * Returns publisher progress for an active topic, routed through the responsible topic-log replicas.
     * A {@code sinceTimestamp} of zero (the default) returns all entries; a positive value keeps entries
     * whose timestamp is strictly greater than that value.
     *
     * @param topicId 64-character hexadecimal topic ID
     * @param sinceTimestamp optional non-negative Unix timestamp in milliseconds
     * @return {@code 200} with a list of signed {@link PublisherProgress} records
     */
    @GET
    @Path(ApiPaths.CLIENT_TOPICS + "/{topicId}/publishers")
    public Response publishers(@PathParam("topicId") String topicId,
                               @QueryParam(ApiParameters.SINCE_TIMESTAMP) String sinceTimestamp) {
        long since = sinceTimestamp == null ? 0 :
                Validators.requireNonNegative(Long.parseLong(sinceTimestamp), ApiParameters.SINCE_TIMESTAMP);
        return json(200, service.publisherProgress(
                HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD), since));
    }

    /**
     * Reads publisher progress from this server's local topic log without routing.
     * This route requires a signed request from an active replication server.
     *
     * @param topicId 64-character hexadecimal topic ID
     * @return {@code 200} with the local list of signed publisher-progress records
     */
    @GET
    @Path(ApiPaths.REPLICATION_TOPICS + "/{topicId}/publishers")
    public Response replicaPublishers(@PathParam("topicId") String topicId) {
        return json(200, service.localPublisherProgress(
                HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD)));
    }

    /**
     * Applies a signed publisher-progress record to this server's local topic log.
     * The request must be signed by an active replication server, and the progress publisher must be
     * authorized for the path topic.
     *
     * @param topicId 64-character hexadecimal topic ID
     * @param body JSON {@link PublisherProgress}, signed by its publisher
     * @return {@code 200} with the accepted progress record
     */
    @POST
    @Path(ApiPaths.REPLICATION_TOPICS + "/{topicId}/publishers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response storeReplicaPublisher(@PathParam("topicId") String topicId, InputStream body) throws IOException {
        String key = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
        PublisherProgress progress = read(body, PublisherProgress.class);
        progressAuthenticator.authenticate(new PublisherProgressAuthenticator.Request(key, progress));
        service.storeProgressReplica(key, progress);
        return json(200, progress);
    }

    /**
     * Reports this server's liveness, identity, active membership snapshot, and locally stored event keys.
     * This read route does not require caller authentication.
     *
     * @return {@code 200} with {@code status}, {@code serverId}, {@code membership}, and {@code eventKeys}
     */
    @GET
    @Path(ApiPaths.OPS_HEALTH)
    public Response health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ApiFields.STATUS, ApiValues.UP);
        result.put(ApiFields.SERVER_ID, self.serverId());
        result.put(ApiFields.MEMBERSHIP, membership.activeServers());
        result.put(ApiFields.EVENT_KEYS, maintenance.localEventKeys());
        return json(200, result);
    }

    /**
     * Queues a repair request for a missing event or topic-log replica. The caller must be an active,
     * authenticated replication server. A successful response means the request was accepted for
     * maintenance processing, not that repair has completed.
     *
     * @param body JSON {@link ReplicaRepairRequest}
     * @return {@code 202} with the accepted request
     */
    @POST
    @Path(ApiPaths.REPLICATION_REPAIR)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response repair(InputStream body) throws IOException {
        ReplicaRepairRequest request = read(body, ReplicaRepairRequest.class);
        maintenance.requestRepair(request);
        return json(202, request);
    }

    /**
     * Returns this server's maintenance snapshot: membership version, suspected and confirmed-down
     * peers, repair tasks, and under-replicated record identifiers. This route is readable without
     * caller authentication.
     *
     * @return {@code 200} with {@code MaintenanceStatus}
     */
    @GET
    @Path(ApiPaths.OPS_MAINTENANCE_STATUS)
    public Response maintenanceStatus() {
        return json(200, maintenance.status());
    }

    /**
     * Returns a bounded inventory of this server's local event replicas and topic logs. This route
     * requires a signed request from an active replication server and is used by peer maintenance.
     *
     * @return {@code 200} with {@code ReplicaInventory}
     */
    @GET
    @Path(ApiPaths.REPLICATION_INVENTORY)
    public Response maintenanceReplicas() {
        return json(200, maintenance.inventory());
    }

    /** Reads one JSON request body, rejecting input larger than {@link HttpRequestBodyReader#MAX_REQUEST_BODY_BYTES}. */
    private static <T> T read(InputStream body, Class<T> type) throws IOException {
        byte[] bytes = HttpRequestBodyReader.readLimited(body);
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
            int status;
            String message;
            if (exception instanceof WebApplicationException web) {
                status = web.getResponse().getStatus();
                message = status == 404 ? HttpErrorCodes.NOT_FOUND
                        : status == 405 ? HttpErrorCodes.METHOD_NOT_ALLOWED : JsonHttp.safeMessage(exception);
            } else if (exception instanceof PersistenceUnavailableException) {
                status = 503;
                message = JsonHttp.safeMessage(exception);
            } else if (exception instanceof SecurityException) {
                status = 403;
                message = "invalid replication caller";
            } else if (exception instanceof HttpRequestBodyReader.RequestBodyTooLargeException) {
                status = 413;
                message = JsonHttp.safeMessage(exception);
            } else if (exception instanceof IllegalArgumentException || exception instanceof IOException) {
                status = 400;
                message = JsonHttp.safeMessage(exception);
            } else {
                status = 500;
                message = JsonHttp.safeMessage(exception);
            }
            LOG.warn("REPLICATION_HTTP_ERROR status={} type={} message={}", status,
                    exception.getClass().getSimpleName(), message, exception);
            return error(status, message);
        }
    }

    /** Stops the listener and interrupts its HTTP worker threads. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
