package org.pubsub.prototype.replication.adapter.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.HttpRequestAuthenticator;
import org.pubsub.prototype.auth.ReplicationHttpRequestSignatures;
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
            ReplicationHttpRequestSignatures.SignatureHeaders signatureHeaders =
                    new ReplicationHttpRequestSignatures.SignatureHeaders(
                            context.getHeaderString(ReplicationHttpRequestSignatures.SERVER_ID_HEADER),
                            context.getHeaderString(ReplicationHttpRequestSignatures.PUBLIC_KEY_HEADER),
                            Long.parseLong(context.getHeaderString(ReplicationHttpRequestSignatures.TIMESTAMP_HEADER)),
                            context.getHeaderString(ReplicationHttpRequestSignatures.NONCE_HEADER),
                            context.getHeaderString(ReplicationHttpRequestSignatures.SIGNATURE_HEADER));
            authenticator.authenticate(new HttpRequestAuthenticator.Request(
                    context.getMethod(), path, body, signatureHeaders));
        } catch (RuntimeException ex) {
            throw new SecurityException("invalid replication caller", ex);
        }
    }
}
