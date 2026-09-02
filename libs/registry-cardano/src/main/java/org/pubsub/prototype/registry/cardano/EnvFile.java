package org.pubsub.prototype.registry.cardano;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class EnvFile {
    private EnvFile() {
    }

    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(file)) {
            return values;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }
}
