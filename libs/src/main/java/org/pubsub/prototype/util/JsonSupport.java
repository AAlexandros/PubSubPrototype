package org.pubsub.prototype.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared JSON mapper for application data, including Java time values. */
public final class JsonSupport {
    /** Configured application JSON mapper. */
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private JsonSupport() {
    }
}
