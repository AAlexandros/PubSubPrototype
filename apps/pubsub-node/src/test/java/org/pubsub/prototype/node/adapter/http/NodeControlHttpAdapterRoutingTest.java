package org.pubsub.prototype.node.adapter.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.service.EventStorageService;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeControlHttpAdapterRoutingTest {
    @TempDir Path temp;

    @Test
    void localEventRoutesReturnOnlyStoredEnvelopes() throws Exception {
        String topic = "11".repeat(32);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope unsigned = EventEnvelope.unsigned(topic,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), 0, 10,
                Base64.getEncoder().encodeToString("payload".getBytes()));
        EventEnvelope event = unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keys.getPrivate()),
                EventCrypto.eventId(unsigned)).withProgressSignature(EventCrypto.signProgress(unsigned, keys.getPrivate()));
        NodeConfig.PersistenceSection config = new NodeConfig.PersistenceSection();
        config.deliveryStatePath = temp.resolve("delivery.json").toString();
        config.connectionTimeoutMs = 1000L;
        config.requestTimeoutMs = 1000L;
        config.retries = 0;
        config.recoveryConcurrency = 1;
        try (EventStorageService storage = new EventStorageService(config, id -> java.util.Optional.empty(), List::of);
             NodeControlHttpAdapter server = new NodeControlHttpAdapter(
                     "127.0.0.1", 0, null, null, null, null, storage, null, 0, Map::of);
             HttpClient client = HttpClient.newHttpClient()) {
            storage.storeLocalSubscriberEvent(event, "LIVE");
            int port = boundPort(server);
            server.start();
            assertEquals(200, send(client, port, "GET", "/v1/events/" + event.eventId()).statusCode());
            assertTrue(send(client, port, "GET", "/v1/events/" + event.eventId()).body().contains(event.payload()));
            assertEquals(200, send(client, port, "GET", "/v1/events?topicId=" + topic).statusCode());
            assertEquals(200, send(client, port, "GET", "/v1/events?topicId=" + topic
                    + "&publisherKeyId=" + EventCrypto.publisherKeyId(event) + "&sequenceNumber=0").statusCode());
            assertEquals(200, send(client, port, "GET", "/v1/events/catch-up/status").statusCode());
            assertEquals(404, send(client, port, "GET", "/v1/events/" + "aa".repeat(32)).statusCode());
        }
    }
    @Test
    void bindsTopicPathAndRejectsWrongMethods() throws Exception {
        try (NodeControlHttpAdapter server = new NodeControlHttpAdapter(
                "127.0.0.1", 0, null, null, null, null, null, null, 0, Map::of);
             HttpClient client = HttpClient.newHttpClient()) {
            int port = boundPort(server);
            server.start();

            HttpResponse<String> invalidTopic = send(client, port, "POST", "/v1/subscriptions/invalid");
            assertEquals(400, invalidTopic.statusCode());
            assertTrue(invalidTopic.body().contains("error"));

            HttpResponse<String> wrongMethod = send(client, port, "DELETE", "/v1/events/publisher-key-id");
            assertEquals(405, wrongMethod.statusCode());
            assertTrue(wrongMethod.body().contains("method_not_allowed"));
        }
    }

    private static int boundPort(NodeControlHttpAdapter server) throws Exception {
        Field field = NodeControlHttpAdapter.class.getDeclaredField("server");
        field.setAccessible(true);
        return ((HttpServer) field.get(server)).getAddress().getPort();
    }

    private static HttpResponse<String> send(HttpClient client, int port, String method, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
