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
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.node.runtimes.DisseminationRuntime;
import org.pubsub.prototype.node.runtimes.NavigationRuntime;
import org.pubsub.prototype.node.runtimes.PersistenceRuntime;
import org.pubsub.prototype.node.service.NodeEventCoordinator;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.sampling.PeerSamplingService;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** HTTP control and inspection routes for one pub-sub node. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public final class EventControlServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final NodeEventCoordinator events;
    private PeerSamplingService sampling;
    private NavigationRuntime navigation;
    private DisseminationRuntime dissemination;
    private PersistenceRuntime persistence;
    private String nodeId;
    private int samplingCapacity;

    public EventControlServer(String host, int port, NodeEventCoordinator events) throws IOException {
        this.events = events;
        ResourceConfig routes = new ResourceConfig()
                .register(this)
                .register(JacksonFeature.class)
                .register(new JsonMapperProvider())
                .register(new JsonErrorMapper());
        this.server = JdkHttpServerFactory.createHttpServer(
                URI.create("http://" + host + ":" + port + "/"), routes, false);
        this.executor = AsyncUtil.singleThread("event-control");
        server.setExecutor(executor);
    }

    public void addSampling(PeerSamplingService sampling, String nodeId, int capacity) {
        this.sampling = sampling;
        this.nodeId = nodeId;
        this.samplingCapacity = capacity;
    }

    public void addNavigation(NavigationRuntime navigation, String nodeId) {
        this.navigation = navigation;
        this.nodeId = nodeId;
    }

    public void addDissemination(DisseminationRuntime dissemination, String nodeId) {
        this.dissemination = dissemination;
        this.nodeId = nodeId;
    }

    public void addPersistence(PersistenceRuntime persistence) {
        this.persistence = persistence;
    }

    public void start() {
        server.start();
    }

    @POST
    @Path(ApiPaths.EVENT_PUBLISH)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response publish(PublishRequest request,
                            @QueryParam(ApiParameters.FORCE_BROADCAST) String forceBroadcast,
                            @QueryParam(ApiParameters.TAMPER_SIGNATURE) String tamperSignature) {
        try {
            EventEnvelope event = events.publish(
                    new TopicId(request.topicId),
                    Base64.getDecoder().decode(request.payload),
                    Boolean.parseBoolean(forceBroadcast),
                    Boolean.parseBoolean(tamperSignature));
            return json(200, new PublishResponse(event.eventId(), event.sequenceNumber(), event));
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @POST
    @Path(ApiPaths.EVENT_INJECT)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response inject(EventEnvelope event) {
        try {
            EventEnvelope injected = events.inject(event);
            return json(200, new PublishResponse(injected.eventId(), injected.sequenceNumber(), injected));
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @GET
    @Path(ApiPaths.PUBLISHER_KEY_ID)
    public Response publisherKeyId() {
        return json(200, Map.of(PUBLISHER_KEY_ID, events.publisherKeyId()));
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
        try {
            navigation.subscribe(new TopicId(topicId).value());
            dissemination.subscribe(topicId);
            return json(200, Map.of(TOPIC_ID, topicId, SUBSCRIBED, true));
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @DELETE
    @Path(ApiPaths.SUBSCRIPTIONS + "/{topicId}")
    public Response unsubscribe(@PathParam("topicId") String topicId) {
        try {
            navigation.unsubscribe(new TopicId(topicId).value());
            dissemination.unsubscribe(topicId);
            return json(200, Map.of(TOPIC_ID, topicId, SUBSCRIBED, false));
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @GET
    @Path(ApiPaths.DISSEMINATION_VIEW)
    public Response disseminationViews() {
        return json(200, Map.of(NODE_ID, nodeId, VIEWS, dissemination.engine().views()));
    }

    @GET
    @Path(ApiPaths.DISSEMINATION_VIEW + "/{topicId}")
    public Response disseminationView(@PathParam("topicId") String topicId) {
        try {
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
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @POST
    @Path(ApiPaths.EVENT_RECOVER + "/{topicId}")
    public Response recover(@PathParam("topicId") String topicId) {
        try {
            return json(200, persistence.recover(new TopicId(topicId).value(), events::acceptRecovered));
        } catch (RuntimeException ex) {
            return error(400, JsonHttp.safeMessage(ex));
        }
    }

    @GET
    @Path(ApiPaths.EVENT_RECOVERY_STATE)
    public Response recoveryState() {
        return json(200, persistence.deliveryState());
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

    public static final class PublishRequest {
        public String topicId;
        public String payload;
    }

    private record PublishResponse(String eventId, long sequenceNumber, EventEnvelope envelope) {
    }
}
