package org.pubsub.prototype.util.files;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.nio.file.Path;

/** Shared JSON file serialization with strict integer decoding and atomic replacement. */
public final class JsonFiles {
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER.copy()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(SerializationFeature.INDENT_OUTPUT);

    static {
        MAPPER.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    private JsonFiles() {
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }

    public static void writeAtomic(Path file, Object value) throws IOException {
        AtomicFiles.write(file, MAPPER.writeValueAsBytes(value));
    }
}
