package org.pubsub.prototype.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict YAML deserialization for application configuration files. */
public final class YamlFiles {
    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private YamlFiles() {
    }

    /** Reads a YAML file into the requested configuration type. */
    public static <T> T read(Path file, Class<T> type) {
        try (InputStream input = Files.newInputStream(file)) {
            return MAPPER.readValue(input, type);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read YAML config " + file, ex);
        }
    }
}
