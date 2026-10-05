package org.pubsub.prototype.cardano;

import org.pubsub.prototype.util.TextFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

/** Reads and writes the {@code KEY=value} files shared with Cardano scripts and processes. */
public final class CardanoEnvironmentFile {

    private static final String KEY_VALUE_SEPARATOR = "=";

    private CardanoEnvironmentFile() {
    }

    /** Reads an environment file, returning an empty map when the file does not exist. */
    public static Map<String, String> read(Path file) {
        requireNonNull(file, "file");
        try {
            Map<String, String> values = new HashMap<>();
            if (!Files.exists(file)) {
                return values;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf(KEY_VALUE_SEPARATOR);
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            return values;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Cardano environment file " + file, ex);
        }
    }

    /** Reads an environment file and verifies that every requested key has a non-blank value. */
    public static Map<String, String> readRequired(Path file, String... requiredKeys) {
        requireNonNull(requiredKeys, "requiredKeys");
        Map<String, String> values = read(file);
        for (String key : requiredKeys) {
            required(values, key);
        }
        return values;
    }

    /** Returns a required non-blank environment value. */
    public static String required(Map<String, String> values, String key) {
        requireNonNull(values, "values");
        requireNonBlank(key, "key");
        return requireNonBlank(values.get(key), key);
    }

    /** Writes the supplied values as one {@code KEY=value} entry per line. */
    public static void write(Path file, Map<String, String> values) {
        requireNonNull(file, "file");
        requireNonNull(values, "values");
        StringBuilder body = new StringBuilder();
        values.forEach((key, value) -> body
                .append(requireNonBlank(key, "key"))
                .append(KEY_VALUE_SEPARATOR)
                .append(requireNonBlank(value, key))
                .append(System.lineSeparator()));
        TextFiles.write(file, body.toString());
    }
}
