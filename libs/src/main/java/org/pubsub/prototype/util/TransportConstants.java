package org.pubsub.prototype.util;

/** Transport limits and constructor field names. */
public final class TransportConstants {
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    public static final String CONFIG_FIELD = "config";
    public static final String IDENTITY_FIELD = "identity";

    private TransportConstants() {
    }
}
