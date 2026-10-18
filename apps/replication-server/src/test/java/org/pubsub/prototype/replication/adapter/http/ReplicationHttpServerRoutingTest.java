package org.pubsub.prototype.replication.adapter.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationHttpServerRoutingTest {
    @Test
    void bindsPathAndQueryParametersAndRejectsWrongMethods() throws Exception {
        try (ReplicationHttpServer server = new ReplicationHttpServer(
                "127.0.0.1", 0, null, null, null, null, event -> null, request -> null, 1);
             HttpClient client = HttpClient.newHttpClient()) {
            int port = boundPort(server);
            server.start();

            HttpResponse<String> invalidKey = send(client, port, "GET", "/v1/client/events/invalid");
            assertEquals(400, invalidKey.statusCode());
            assertTrue(invalidKey.body().contains("error"));

            HttpResponse<String> missingTopic = send(client, port, "GET", "/v1/client/events/" + "0".repeat(64));
            assertEquals(400, missingTopic.statusCode());

            HttpResponse<String> invalidSince = send(client, port, "GET",
                    "/v1/client/topics/" + "0".repeat(64) + "/publishers?sinceTimestamp=-1");
            assertEquals(400, invalidSince.statusCode());

            HttpResponse<String> wrongMethod = send(client, port, "POST", "/v1/ops/health");
            assertEquals(405, wrongMethod.statusCode());
            assertTrue(wrongMethod.body().contains("method_not_allowed"));
        }
    }

    private static int boundPort(ReplicationHttpServer server) throws Exception {
        Field field = ReplicationHttpServer.class.getDeclaredField("server");
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
