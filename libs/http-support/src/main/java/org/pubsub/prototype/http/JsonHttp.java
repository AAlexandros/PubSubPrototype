package org.pubsub.prototype.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonHttp {
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String JSON_MEDIA_TYPE = "application/json";

    public static <T> T read(HttpExchange exchange, ObjectMapper mapper, Class<T> type) throws IOException {
        return mapper.readValue(exchange.getRequestBody(), type);
    }

    public static <T> T readBounded(HttpExchange exchange, ObjectMapper mapper, Class<T> type, int maxBytes)
            throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("request payload too large");
        return mapper.readValue(bytes, type);
    }

    public static void respond(HttpExchange exchange, ObjectMapper mapper, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set(CONTENT_TYPE, JSON_MEDIA_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static String suffix(URI uri, String prefix) {
        String path = uri.getPath();
        if (path.equals(prefix) || !path.startsWith(prefix + "/")) return "";
        return path.substring(prefix.length() + 1);
    }

    public static long queryLong(URI uri, String name, long fallback) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return fallback;
        for (String part : rawQuery.split("&")) {
            String[] pieces = part.split("=", 2);
            if (pieces[0].equals(name)) {
                long parsed = Long.parseLong(pieces.length == 2 ? pieces[1] : "");
                if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
                return parsed;
            }
        }
        return fallback;
    }

    public static Map<String, String> query(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            String[] pieces = part.split("=", 2);
            result.put(pieces[0], pieces.length == 2 ? pieces[1] : "true");
        }
        return result;
    }

    public static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private JsonHttp() {
    }
}
