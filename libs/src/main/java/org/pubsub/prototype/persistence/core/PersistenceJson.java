package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class PersistenceJson {
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private PersistenceJson() {
    }
}
