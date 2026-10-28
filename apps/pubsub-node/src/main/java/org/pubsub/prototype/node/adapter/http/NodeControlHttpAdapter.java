package org.pubsub.prototype.node.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.HttpErrorCodes;
import org.pubsub.prototype.http.HttpRequestBodyReader;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.node.service.DisseminationService;
import org.pubsub.prototype.node.service.NavigationService;
import org.pubsub.prototype.node.service.EventStorageService;
import org.pubsub.prototype.node.service.EventProcessingService;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.sampling.PeerSamplingService;
import org.pubsub.prototype.util.files.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;

import static org.pubsub.prototype.http.ApiFields.CAPACITY;
import static org.pubsub.prototype.http.ApiFields.FINGER_TOPICS;
import static org.pubsub.prototype.http.ApiFields.NODE_ID;
import static org.pubsub.prototype.http.ApiFields.PREDECESSOR;
import static org.pubsub.prototype.http.ApiFields.PUBLISHER_KEY_ID;
import static org.pubsub.prototype.http.ApiFields.RANDOM_PEERS;
import static org.pubsub.prototype.http.ApiFields.SUBSCRIBED;
import static org.pubsub.prototype.http.ApiFields.SUBSCRIPTIONS;
import static org.pubsub.prototype.http.ApiFields.SUCCESSOR;
import static org.pubsub.prototype.http.ApiFields.TOPIC_ID;
import static org.pubsub.prototype.http.ApiFields.TOPIC_INDEX;
import static org.pubsub.prototype.http.ApiFields.VIEW;
import static org.pubsub.prototype.http.ApiFields.VIEWS;

/** Local HTTP adapter used by the pub-sub node CLI for commands and inspection. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public final class NodeControlHttpAdapter implements AutoCloseable {

    // Standard logger for the class
    private static final Logger LOG = LoggerFactory.getLogger(NodeControlHttpAdapter.class);

    private final HttpServer server;
    private final String bindHost;
    private final ExecutorService executor;
    private final EventProcessingService events;
    private final PeerSamplingService sampling;
    private final NavigationService navigation;
    private final DisseminationService dissemination;
    private final EventStorageService eventStorage;
    private final String nodeId;
    private final int samplingCapacity;

    public NodeControlHttpAdapter(String host, int port, EventProcessingService events,
                                  PeerSamplingService sampling, NavigationService navigation,
                                  DisseminationService dissemination,
                                  EventStorageService eventStorage,
                                  String nodeId, int samplingCapacity) throws IOException {
        this.bindHost = host;
        this.events = events;
        this.sampling = sampling;
        this.navigation = navigation;
        this.dissemination = dissemination;
        this.eventStorage = eventStorage;
        this.nodeId = nodeId;
        this.samplingCapacity = samplingCapacity;
        ResourceConfig routes = new ResourceConfig()
                .register(this)
                .register(JacksonFeature.class)
                .register(new JsonMapperProvider())
                .register(new ApiCallLogger())
                .register(new JsonErrorMapper());
        this.server = JdkHttpServerFactory.createHttpServer(
                URI.create("http://" + host + ":" + port + "/"), routes, false);
        this.executor = AsyncUtil.singleThread("event-control");
        server.setExecutor(executor);
        NodeLog.init(LOG, "HTTP_CONTROL_CONFIGURED bind={} port={} samplingCapacity={}",
                bindHost, port, samplingCapacity);
    }

    public void start() {
        server.start();
        NodeLog.init(LOG, "HTTP_CONTROL_LISTENING bind={} port={}", bindHost, server.getAddress().getPort());
    }

    /** Accepts a local publication; query flags are acceptance-test controls. */
    @POST
    @Path(ApiPaths.EVENT_PUBLISH)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response publish(PublishRequest request,
                            @QueryParam(ApiParameters.FORCE_BROADCAST) String forceBroadcast,
                            @QueryParam(ApiParameters.TAMPER_SIGNATURE) String tamperSignature) {
        EventEnvelope event = events.publishEvent(
                new TopicId(request.topicId),
                Base64.getDecoder().decode(request.payload),
                Boolean.parseBoolean(forceBroadcast),
                Boolean.parseBoolean(tamperSignature));
        return json(200, new PublishResponse(event.eventId(), event.sequenceNumber(), event));
    }

    /** Replays a supplied envelope for acceptance testing without local validation. */
    @POST
    @Path(ApiPaths.EVENT_INJECT)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response replayEventForAcceptance(EventEnvelope event) {
        LOG.info("EVENT_INJECT_IN eventId={} topicId={} sequenceNumber={}",
                event.eventId(), event.topicId(), event.sequenceNumber());
        dissemination.sendEventToTopicPeers(event, null);
        LOG.info("EVENT_INJECT_OUT eventId={}", event.eventId());
        return json(200, new PublishResponse(event.eventId(), event.sequenceNumber(), event));
    }

    @GET
    @Path(ApiPaths.PUBLISHER_KEY_ID)
    public Response publisherKeyId() {
        return json(200, Map.of(PUBLISHER_KEY_ID, events.getLocalPublisherKeyId()));
    }

    @GET
    @Path(ApiPaths.PEER_SAMPLING_VIEW)
    public Response peerSamplingView() {
        return json(200, Map.of(NODE_ID, nodeId, CAPACITY, samplingCapacity, VIEW, sampling.view()));
    }

    @GET
    @Path(ApiPaths.NAVIGATION_VIEW)
    public Response navigationView() {
        var engine = navigation.navigation();
        return json(200, Map.of(
                NODE_ID, nodeId,
                TOPIC_INDEX, engine.topicIndex().topicIds(),
                SUBSCRIPTIONS, engine.subscriptions(),
                FINGER_TOPICS, engine.fingerTopics(),
                VIEW, engine.view()));
    }

    @GET
    @Path(ApiPaths.SUBSCRIPTIONS)
    public Response subscriptions() {
        return json(200, Map.of(NODE_ID, nodeId, SUBSCRIPTIONS, navigation.navigation().subscriptions()));
    }

    @POST
    @Path(ApiPaths.SUBSCRIPTIONS + "/{topicId}")
    public Response subscribe(@PathParam("topicId") String topicId) {
        navigation.subscribe(new TopicId(topicId).value());
        dissemination.subscribe(topicId);
        return json(200, Map.of(TOPIC_ID, topicId, SUBSCRIBED, true));
    }

    @DELETE
    @Path(ApiPaths.SUBSCRIPTIONS + "/{topicId}")
    public Response unsubscribe(@PathParam("topicId") String topicId) {
        navigation.unsubscribe(new TopicId(topicId).value());
        dissemination.unsubscribe(topicId);
        return json(200, Map.of(TOPIC_ID, topicId, SUBSCRIBED, false));
    }

    @GET
    @Path(ApiPaths.DISSEMINATION_VIEW)
    public Response disseminationViews() {
        return json(200, Map.of(NODE_ID, nodeId, VIEWS, dissemination.engine().views()));
    }

    @GET
    @Path(ApiPaths.DISSEMINATION_VIEW + "/{topicId}")
    public Response disseminationView(@PathParam("topicId") String topicId) {
        String validTopicId = new TopicId(topicId).value();
        var view = dissemination.engine().view(validTopicId);
        if (view.isEmpty()) {
            return json(404, Map.of(
                    HttpErrorCodes.ERROR, "dissemination_view_not_found", TOPIC_ID, validTopicId));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(NODE_ID, nodeId);
        response.put(TOPIC_ID, view.get().topicId());
        response.put(PREDECESSOR, view.get().predecessor());
        response.put(SUCCESSOR, view.get().successor());
        response.put(RANDOM_PEERS, view.get().randomPeers());
        return json(200, response);
    }

    @POST
    @Path(ApiPaths.EVENT_RECOVER + "/{topicId}")
    public Response recover(@PathParam("topicId") String topicId) {
        return json(200, eventStorage.recover(new TopicId(topicId).value(), events::validateRecoveredEvent));
    }

    @GET
    @Path(ApiPaths.EVENT_RECOVERY_STATE)
    public Response recoveryState() {
        return json(200, eventStorage.deliveryState());
    }

    private static Response json(int status, Object body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Response error(int status, String message) {
        return json(status, Map.of(HttpErrorCodes.ERROR, message));
    }

    /** Logs every local API call, including requests rejected before a resource method runs. */
    private static final class ApiCallLogger implements ContainerRequestFilter, ContainerResponseFilter {
        private static final AtomicLong NEXT_ID = new AtomicLong();
        private static final String CALL_ID = "apiCallId";
        private static final String START_NANOS = "apiStartNanos";

        @Override
        public void filter(ContainerRequestContext request) {
            long callId = NEXT_ID.incrementAndGet();
            request.setProperty(CALL_ID, callId);
            request.setProperty(START_NANOS, System.nanoTime());
            NodeLog.network(LOG, "API_IN callId={} method={} path={} query={}", callId, request.getMethod(),
                    request.getUriInfo().getPath(), request.getUriInfo().getRequestUri().getRawQuery());
        }

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            if (request.getProperty(CALL_ID) == null) {
                // Jersey can skip request filters when routing fails (for example, HTTP 405).
                request.setProperty(CALL_ID, NEXT_ID.incrementAndGet());
                NodeLog.network(LOG, "API_IN callId={} method={} path={} query={}", request.getProperty(CALL_ID),
                        request.getMethod(), request.getUriInfo().getPath(),
                        request.getUriInfo().getRequestUri().getRawQuery());
            }
            Long start = (Long) request.getProperty(START_NANOS);
            long elapsedMs = start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
            Object entity = response.getEntity();
            NodeLog.network(LOG, "API_OUT callId={} method={} path={} status={} resultType={} result={} elapsedMs={}",
                    request.getProperty(CALL_ID), request.getMethod(), request.getUriInfo().getPath(),
                    response.getStatus(), entity == null ? "none" : entity.getClass().getSimpleName(),
                    summarize(entity), elapsedMs);
        }

        private static String summarize(Object entity) {
            String value;
            if (entity instanceof PublishResponse published) {
                value = "eventId=" + published.eventId() + " sequenceNumber=" + published.sequenceNumber();
            } else if (entity instanceof Map<?, ?> || entity instanceof org.pubsub.prototype.persistence.RecoveryResult) {
                value = String.valueOf(entity);
            } else {
                value = "omitted";
            }
            value = value.replace('\n', ' ').replace('\r', ' ');
            return value.length() <= 1000 ? value : value.substring(0, 1000) + "...";
        }
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
            LOG.warn("NODE_CONTROL_HTTP_ERROR status={} type={} message={}", status,
                    exception.getClass().getSimpleName(), message, exception);
            return error(status, message);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static final class PublishRequest {
        public String topicId;
        public String payload;
    }

    private record PublishResponse(String eventId, long sequenceNumber, EventEnvelope envelope) {
    }
}
