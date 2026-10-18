package org.pubsub.prototype.auth;

/** Verifies an incoming claim and returns the identity or context established by it. */
@FunctionalInterface
public interface Authenticator<Request, Result> {
    Result authenticate(Request request);
}
