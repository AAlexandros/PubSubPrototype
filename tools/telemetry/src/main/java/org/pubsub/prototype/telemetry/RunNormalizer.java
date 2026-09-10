package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RunNormalizer {
    static void normalize(Path runDir) throws IOException {
        Path raw = runDir.resolve(TelemetryLayout.RAW_DIRECTORY);
        if (!Files.isDirectory(raw)) throw new IllegalArgumentException("Missing raw telemetry directory: " + raw);
        Files.createDirectories(runDir.resolve(TelemetryLayout.PARQUET_DIRECTORY));
        System.err.println("[phase-0.9] Normalizing " + runDir);
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path input = TelemetryLayout.rawDataset(runDir, dataset.name());
            if (!Files.exists(input)) throw new IllegalArgumentException("Missing required dataset: " + input);
            writeParquet(input, TelemetryLayout.parquetDataset(runDir, dataset.name()), dataset);
            System.err.println("[phase-0.9]   wrote " + dataset.name() + TelemetryLayout.PARQUET_EXTENSION);
        }
        SummaryWriter.write(runDir);
        System.err.println("[phase-0.9]   wrote derived summaries");
    }

    private static void writeParquet(Path input, Path output, DatasetCatalog.Dataset dataset) throws IOException {
        Files.deleteIfExists(output);
        Schema schema = avroSchema(dataset);
        long count = 0;
        try (var writer = AvroParquetWriter.<GenericRecord>builder(new LocalOutputFile(output))
                .withSchema(schema).withCompressionCodec(CompressionCodecName.UNCOMPRESSED).build();
             BufferedReader lines = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node;
                try {
                    node = TelemetryJson.MAPPER.readTree(line);
                } catch (IOException ex) {
                    throw new IllegalArgumentException(input + ": invalid JSONL record " + (count + 1), ex);
                }
                GenericRecord record = new GenericData.Record(schema);
                for (DatasetCatalog.Field field : dataset.fields()) {
                    JsonNode value = node.get(field.name());
                    if ((value == null || value.isNull()) && !field.nullable()) {
                        throw new IllegalArgumentException(input + ": record " + (count + 1)
                                + " missing " + field.name());
                    }
                    record.put(field.name(), convert(value, field, input, count + 1));
                }
                writer.write(record);
                count++;
            }
        }
        if (count == 0 && dataset.name().equals("runs")) {
            throw new IllegalArgumentException(input + " must contain a run record");
        }
    }

    private static Object convert(JsonNode value, DatasetCatalog.Field field, Path input, long line) {
        if (value == null || value.isNull()) return null;
        try {
            return switch (field.type()) {
                case STRING -> value.isTextual() ? value.textValue() : value.toString();
                case LONG -> value.longValue();
                case DOUBLE -> value.doubleValue();
                case BOOLEAN -> value.booleanValue();
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(input + ": record " + line + " invalid " + field.name(), ex);
        }
    }

    private static Schema avroSchema(DatasetCatalog.Dataset dataset) {
        Schema record = Schema.createRecord(safeName(dataset.name()), dataset.meaning(),
                "org.pubsub.prototype.telemetry", false);
        List<Schema.Field> fields = new ArrayList<>();
        for (DatasetCatalog.Field field : dataset.fields()) {
            Schema primitive = switch (field.type()) {
                case STRING -> Schema.create(Schema.Type.STRING);
                case LONG -> Schema.create(Schema.Type.LONG);
                case DOUBLE -> Schema.create(Schema.Type.DOUBLE);
                case BOOLEAN -> Schema.create(Schema.Type.BOOLEAN);
            };
            Schema type = field.nullable()
                    ? Schema.createUnion(List.of(Schema.create(Schema.Type.NULL), primitive)) : primitive;
            fields.add(new Schema.Field(field.name(), type, field.meaning(),
                    field.nullable() ? Schema.Field.NULL_DEFAULT_VALUE : null));
        }
        record.setFields(fields);
        return record;
    }

    private static String safeName(String name) {
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private RunNormalizer() {
    }
}
