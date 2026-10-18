package org.pubsub.prototype.auth;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Verifies signed HTTP requests and rejects a nonce already used at this receiver. */
public final class HttpRequestAuthenticator
        implements Authenticator<HttpRequestAuthenticator.Request, String> {
    private final Supplier<Set<String>> allowedIds;
    private final String audience;
    private final Clock clock;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public HttpRequestAuthenticator(Supplier<Set<String>> allowedIds, String audience, Clock clock) {
        this.allowedIds = allowedIds;
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public String authenticate(Request request) {
        SignedHttpRequest.Headers headers = request.headers();
        if (!SignedHttpRequest.verify(headers, request.method(), request.path(), request.body(), audience,
                allowedIds.get(), clock))
            throw new SecurityException("invalid request signature");
        long now = clock.millis();
        seenNonces.entrySet().removeIf(entry -> entry.getValue() < now);
        if (seenNonces.putIfAbsent(headers.signerId() + ":" + headers.nonce(),
                headers.timestamp() + SignedHttpRequest.MAX_CLOCK_SKEW_MS) != null)
            throw new SecurityException("replayed request");
        return headers.signerId();
    }

    public record Request(String method, String path, byte[] body, SignedHttpRequest.Headers headers) { }
}
