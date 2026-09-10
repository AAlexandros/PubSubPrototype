package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void validatesAndNormalizesEveryRequiredDatasetWithoutChangingRawInput() throws Exception {
        Path run = temporary.resolve("scenario/run");
        Path raw = run.resolve("raw");
        Files.createDirectories(raw);
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            ObjectNode record = JSON.createObjectNode();
            for (DatasetCatalog.Field field : dataset.fields()) {
                if (field.nullable()) record.putNull(field.name());
                else switch (field.type()) {
                    case STRING -> record.put(field.name(), "value");
                    case LONG -> record.put(field.name(), 1L);
                    case DOUBLE -> record.put(field.name(), 1.0);
                    case BOOLEAN -> record.put(field.name(), true);
                }
            }
            Files.writeString(raw.resolve(dataset.name() + ".jsonl"), record + "\n", StandardCharsets.UTF_8);
        }
        String before = Files.readString(raw.resolve("event_lifecycle.jsonl"));
        TelemetryTool.normalizeRun(run);
        assertEquals(before, Files.readString(raw.resolve("event_lifecycle.jsonl")));
        for (String name : DatasetCatalog.ALL.keySet()) {
            byte[] parquet = Files.readAllBytes(run.resolve("parquet").resolve(name + ".parquet"));
            assertEquals("PAR1", new String(parquet, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("PAR1", new String(parquet, parquet.length - 4, 4, StandardCharsets.US_ASCII));
        }
        assertTrue(Files.readString(run.resolve("derived/summary.json")).contains("deliveryHitRatio"));
    }

    @Test
    void generatesACompleteDataDictionary() throws Exception {
        Path dictionary = temporary.resolve("data-dictionary.md");
        TelemetryTool.writeDictionary(dictionary);
        String value = Files.readString(dictionary);
        for (String name : DatasetCatalog.ALL.keySet()) assertTrue(value.contains("## `" + name + "`"));
        assertTrue(value.contains("| Field | Type | Unit | Meaning | Nullable | Source component |"));
    }
}
