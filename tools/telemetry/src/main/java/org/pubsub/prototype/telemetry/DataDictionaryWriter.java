package org.pubsub.prototype.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class DataDictionaryWriter {
    static void write(Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        StringBuilder text = new StringBuilder("# Phase 0.9 telemetry data dictionary\n\n")
                .append("Parquet is the canonical analysis format. JSONL files are the authoritative raw observations.\n\n");
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            text.append("## `").append(dataset.name()).append("`\n\n").append(dataset.meaning()).append(".\n\n")
                    .append("| Field | Type | Unit | Meaning | Nullable | Source component |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n");
            for (DatasetCatalog.Field field : dataset.fields()) {
                text.append("| `").append(field.name()).append("` | ")
                        .append(field.type().name().toLowerCase(Locale.ROOT))
                        .append(" | ").append(field.unit()).append(" | ").append(field.meaning()).append(" | ")
                        .append(field.nullable() ? "yes" : "no").append(" | ").append(field.source()).append(" |\n");
            }
            text.append('\n');
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private DataDictionaryWriter() {
    }
}
