package org.pubsub.prototype.auth;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Authenticates an internal replication request using its signature and the
 * current server membership. A nonce accepted once at this receiver cannot be
 * reused while the signed timestamp remains valid.
 */
public final class HttpRequestAuthenticator
        implements Authenticator<HttpRequestAuthenticator.Request, String> {
    private final Supplier<Set<String>> activeServerIds;
    private final String receiverServerId;
    private final Clock clock;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public HttpRequestAuthenticator(Supplier<Set<String>> activeServerIds, String receiverServerId, Clock clock) {
        this.activeServerIds = activeServerIds;
        this.receiverServerId = receiverServerId;
        this.clock = clock;
    }

    @Override
    public String authenticate(Request request) {
        ReplicationHttpRequestSignatures.SignatureHeaders headers = request.signatureHeaders();
        if (!ReplicationHttpRequestSignatures.verifyRequestSignature(headers, request.method(),
                request.rawPathAndQuery(), request.body(), receiverServerId, activeServerIds.get(), clock))
            throw new SecurityException("invalid request signature");
        long now = clock.millis();
        seenNonces.entrySet().removeIf(entry -> entry.getValue() < now);
        if (seenNonces.putIfAbsent(headers.signerServerId() + ":" + headers.nonce(),
                headers.timestampMillis() + ReplicationHttpRequestSignatures.MAX_CLOCK_SKEW_MS) != null)
            throw new SecurityException("replayed request");
        return headers.signerServerId();
    }

    /** Exact wire request and signature headers to authenticate. */
    public record Request(String method, String rawPathAndQuery, byte[] body,
                          ReplicationHttpRequestSignatures.SignatureHeaders signatureHeaders) { }
}
