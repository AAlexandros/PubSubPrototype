package org.pubsub.prototype.node.adapter.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventControlServerRoutingTest {
    @Test
    void bindsTopicPathAndRejectsWrongMethods() throws Exception {
        try (EventControlServer server = new EventControlServer("127.0.0.1", 0, null);
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

    private static int boundPort(EventControlServer server) throws Exception {
        Field field = EventControlServer.class.getDeclaredField("server");
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
