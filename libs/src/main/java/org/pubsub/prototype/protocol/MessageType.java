package org.pubsub.prototype.protocol;

/**
 * Application-level messages exchanged between Pub/Sub nodes.
 *
 * <p>Handshake and ping messages establish and monitor a connection.
 * SecureCyclon, Navigation, and Dissemination exchanges maintain the overlay;
 * {@link #EVENT} carries the actual published data over it.</p>
 */
public enum MessageType {
    /** Begins peer authentication and endpoint discovery on a new connection. */
    HELLO,
    /** Confirms a {@link #HELLO} and makes the connection usable. */
    HELLO_ACK,
    /** Checks that an activated/valid peer connection is still alive. */
    PING,
    /** Replies to a {@link #PING} and supports round-trip-time measurement. */
    PONG,
    /** Requests a SecureCyclon peer-sampling exchange. */
    SECURECYCLON_REQUEST,
    /** Returns the result of a SecureCyclon peer-sampling exchange. */
    SECURECYCLON_RESPONSE,
    /** Propagates evidence of a SecureCyclon protocol violation. */
    SECURECYCLON_PROOF,
    /** Carries one signed event for validation and forwarding to subscribers. */
    EVENT,
    /** Requests a topic-aware Navigation exchange. */
    NAVIGATION_REQUEST,
    /** Returns the result of a topic-aware Navigation exchange. */
    NAVIGATION_RESPONSE,
    /** Requests a Dissemination exchange to maintain event-forwarding links. */
    DISSEMINATION_REQUEST,
    /** Returns the result of a Dissemination exchange. */
    DISSEMINATION_RESPONSE
}
