package org.pubsub.prototype.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

/** Shared JSON file serialization with atomic replacement. */
public final class JsonFiles {
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER.copy()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonFiles() {
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }

    public static void writeAtomic(Path file, Object value) throws IOException {
        AtomicFiles.write(file, MAPPER.writeValueAsBytes(value));
    }
}
