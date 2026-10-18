package org.pubsub.prototype.replication.adapter.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.HttpRequestAuthenticator;
import org.pubsub.prototype.auth.SignedHttpRequest;
import org.pubsub.prototype.http.ApiPaths;
import org.pubsub.prototype.http.HttpRequestBodyReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Authenticates every internal replication route before Jersey invokes its endpoint. */
@Priority(Priorities.AUTHENTICATION)
public final class ReplicaRequestFilter implements ContainerRequestFilter {
    private final Authenticator<HttpRequestAuthenticator.Request, String> authenticator;

    public ReplicaRequestFilter(Authenticator<HttpRequestAuthenticator.Request, String> authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (!path.startsWith(ApiPaths.REPLICATION_ROOT + "/")) return;
        byte[] body = HttpRequestBodyReader.readLimited(context.getEntityStream());
        context.setEntityStream(new ByteArrayInputStream(body));
        String query = context.getUriInfo().getRequestUri().getRawQuery();
        if (query != null) path += "?" + query;
        try {
            SignedHttpRequest.Headers signed = new SignedHttpRequest.Headers(
                    context.getHeaderString(SignedHttpRequest.ID_HEADER),
                    context.getHeaderString(SignedHttpRequest.KEY_HEADER),
                    Long.parseLong(context.getHeaderString(SignedHttpRequest.TIME_HEADER)),
                    context.getHeaderString(SignedHttpRequest.NONCE_HEADER),
                    context.getHeaderString(SignedHttpRequest.SIGNATURE_HEADER));
            authenticator.authenticate(new HttpRequestAuthenticator.Request(context.getMethod(), path, body, signed));
        } catch (RuntimeException ex) {
            throw new SecurityException("invalid replication caller", ex);
        }
    }
}
