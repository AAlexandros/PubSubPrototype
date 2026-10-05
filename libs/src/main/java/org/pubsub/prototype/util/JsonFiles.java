package org.pubsub.prototype.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        Path absoluteFile = file.toAbsolutePath();
        Path directory = absoluteFile.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, absoluteFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
