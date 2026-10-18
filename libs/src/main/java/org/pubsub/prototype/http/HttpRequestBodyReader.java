package org.pubsub.prototype.http;

import java.io.IOException;
import java.io.InputStream;

/** Reads HTTP request bodies with a shared size limit. */
public final class HttpRequestBodyReader {
    public static final int MAX_REQUEST_BODY_BYTES = 4 * 1024 * 1024;

    /** Reads one request body, throwing if it exceeds {@link #MAX_REQUEST_BODY_BYTES}. */
    public static byte[] readLimited(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BODY_BYTES) throw new RequestBodyTooLargeException();
        return bytes;
    }

    /** Indicates that a request body exceeded the shared size limit. */
    public static final class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException() {
            super("request payload too large");
        }
    }

    private HttpRequestBodyReader() {
    }
}
