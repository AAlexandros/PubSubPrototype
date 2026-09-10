package org.pubsub.prototype.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.ApiParameters;
import org.pubsub.prototype.http.HttpErrorCodes;
import org.pubsub.prototype.http.HttpMethods;
import org.pubsub.prototype.http.JsonHttp;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.sampling.PeerSamplingService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class EventControlServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final NodeEventService events;
    private DisseminationRuntime dissemination;
    private NodePersistenceRuntime persistence;

    EventControlServer(String host, int port, NodeEventService events) throws IOException {
        this.events = events;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext(ApiPaths.EVENT_PUBLISH, this::publish);
        this.server.createContext(ApiPaths.EVENT_INJECT, this::inject);
        this.server.createContext(ApiPaths.PUBLISHER_KEY_ID, this::publisherKeyId);
        this.server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-control");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void addSampling(PeerSamplingService sampling, String nodeId, int capacity) {
        server.createContext(ApiPaths.PEER_SAMPLING_VIEW, exchange -> {
            if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            respond(exchange, 200, Map.of("nodeId", nodeId, "capacity", capacity, "view", sampling.view()));
        });
    }

    void addNavigation(NavigationRuntime navigation, String nodeId) {
        server.createContext(ApiPaths.NAVIGATION_VIEW, exchange -> {
            if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            var engine = navigation.engine();
            respond(exchange, 200, Map.of(
                    "nodeId", nodeId,
                    "topicOrdering", engine.ordering().topicIds(),
                    "subscriptions", engine.subscriptions(),
                    "fingerTopics", engine.fingerTopics(),
                    "view", engine.view()
            ));
        });
        server.createContext(ApiPaths.SUBSCRIPTIONS, exchange -> {
            String topicId = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.SUBSCRIPTIONS);
            try {
                switch (exchange.getRequestMethod()) {
                    case HttpMethods.GET -> {
                        if (!topicId.isEmpty()) {
                            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                            return;
                        }
                        respond(exchange, 200, Map.of("nodeId", nodeId, "subscriptions", navigation.engine().subscriptions()));
                    }
                    case HttpMethods.POST -> {
                        if (topicId.isEmpty()) {
                            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, "topicId is required"));
                            return;
                        }
                        navigation.subscribe(new TopicId(topicId).value());
                        if (dissemination != null) dissemination.subscribe(topicId);
                        respond(exchange, 200, Map.of("topicId", topicId, "subscribed", true));
                    }
                    case HttpMethods.DELETE -> {
                        if (topicId.isEmpty()) {
                            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, "topicId is required"));
                            return;
                        }
                        navigation.unsubscribe(new TopicId(topicId).value());
                        if (dissemination != null) dissemination.unsubscribe(topicId);
                        respond(exchange, 200, Map.of("topicId", topicId, "subscribed", false));
                    }
                    default -> respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                }
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, ex.getMessage()));
            }
        });
    }

    void addDissemination(DisseminationRuntime dissemination, String nodeId) {
        this.dissemination = dissemination;
        server.createContext(ApiPaths.DISSEMINATION_VIEW, exchange -> {
            if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            String topicId = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.DISSEMINATION_VIEW);
            if (topicId.isEmpty()) {
                respond(exchange, 200, Map.of("nodeId", nodeId, "views", dissemination.engine().views()));
                return;
            }
            try {
                String validTopicId = new TopicId(topicId).value();
                var view = dissemination.engine().view(validTopicId);
                if (view.isEmpty()) {
                    respond(exchange, 404, Map.of(HttpErrorCodes.ERROR, "dissemination_view_not_found", "topicId", validTopicId));
                } else {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("nodeId", nodeId);
                    response.put("topicId", view.get().topicId());
                    response.put("predecessor", view.get().predecessor());
                    response.put("successor", view.get().successor());
                    response.put("randomPeers", view.get().randomPeers());
                    respond(exchange, 200, response);
                }
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, ex.getMessage()));
            }
        });
    }

    void addPersistence(NodePersistenceRuntime persistence) {
        this.persistence = persistence;
        server.createContext(ApiPaths.EVENT_RECOVER, exchange -> {
            if (!HttpMethods.POST.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            String topicId = JsonHttp.suffix(exchange.getRequestURI(), ApiPaths.EVENT_RECOVER);
            try {
                if (topicId.isEmpty()) throw new IllegalArgumentException("topicId is required");
                respond(exchange, 200, persistence.recover(new TopicId(topicId).value()));
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, JsonHttp.safeMessage(ex)));
            }
        });
        server.createContext(ApiPaths.EVENT_RECOVERY_STATE, exchange -> {
            if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
                return;
            }
            respond(exchange, 200, persistence.deliveryState());
        });
    }

    void start() {
        server.start();
    }

    private void publish(HttpExchange exchange) throws IOException {
        if (!HttpMethods.POST.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        try {
            PublishRequest request = JsonHttp.read(exchange, MAPPER, PublishRequest.class);
            Map<String, String> query = JsonHttp.query(exchange.getRequestURI());
            EventEnvelope event = events.publish(
                    new TopicId(request.topicId),
                    Base64.getDecoder().decode(request.payload),
                    Boolean.parseBoolean(query.getOrDefault(ApiParameters.FORCE_BROADCAST, "false")),
                    Boolean.parseBoolean(query.getOrDefault(ApiParameters.TAMPER_SIGNATURE, "false"))
            );
            respond(exchange, 200, new PublishResponse(event.eventId(), event.sequenceNumber(), event));
        } catch (RuntimeException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, ex.getMessage()));
        }
    }

    private void publisherKeyId(HttpExchange exchange) throws IOException {
        if (!HttpMethods.GET.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        respond(exchange, 200, Map.of("publisherKeyId", events.publisherKeyId()));
    }

    private void inject(HttpExchange exchange) throws IOException {
        if (!HttpMethods.POST.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of(HttpErrorCodes.ERROR, HttpErrorCodes.METHOD_NOT_ALLOWED));
            return;
        }
        try {
            EventEnvelope event = JsonHttp.read(exchange, MAPPER, EventEnvelope.class);
            EventEnvelope injected = events.inject(event);
            respond(exchange, 200, new PublishResponse(injected.eventId(), injected.sequenceNumber(), injected));
        } catch (RuntimeException ex) {
            respond(exchange, 400, Map.of(HttpErrorCodes.ERROR, ex.getMessage()));
        }
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        JsonHttp.respond(exchange, MAPPER, status, body);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static final class PublishRequest {
        public String topicId;
        public String payload;
    }

    private record PublishResponse(String eventId, long sequenceNumber, EventEnvelope envelope) {
    }
}
