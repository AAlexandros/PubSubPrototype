package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.api.Binary;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TelemetryTool {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TelemetryTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "normalize" -> normalizeRun(Path.of(args[1]));
            case "aggregate" -> aggregate(Path.of(args[1]));
            case "dictionary" -> writeDictionary(Path.of(args[1]));
            default -> usage();
        }
    }

    private static void usage() {
        throw new IllegalArgumentException("Usage: telemetry <normalize RUN_DIR | aggregate RESULTS_DIR | dictionary OUTPUT>");
    }

    static void normalizeRun(Path runDir) throws IOException {
        Path raw = runDir.resolve("raw");
        if (!Files.isDirectory(raw)) throw new IllegalArgumentException("Missing raw telemetry directory: " + raw);
        Path parquet = runDir.resolve("parquet");
        Files.createDirectories(parquet);
        System.err.println("[phase-0.9] Normalizing " + runDir);
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path input = raw.resolve(dataset.name() + ".jsonl");
            if (!Files.exists(input)) throw new IllegalArgumentException("Missing required dataset: " + input);
            writeParquet(input, parquet.resolve(dataset.name() + ".parquet"), dataset);
            System.err.println("[phase-0.9]   wrote " + dataset.name() + ".parquet");
        }
        writeSummary(runDir);
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
                try { node = JSON.readTree(line); }
                catch (IOException ex) { throw new IllegalArgumentException(input + ": invalid JSONL record " + (count + 1), ex); }
                GenericRecord record = new GenericData.Record(schema);
                for (DatasetCatalog.Field field : dataset.fields()) {
                    JsonNode value = node.get(field.name());
                    if ((value == null || value.isNull()) && !field.nullable()) {
                        throw new IllegalArgumentException(input + ": record " + (count + 1) + " missing " + field.name());
                    }
                    record.put(field.name(), convert(value, field, input, count + 1));
                }
                writer.write(record);
                count++;
            }
        }
        if (count == 0 && dataset.name().equals("runs")) throw new IllegalArgumentException(input + " must contain a run record");
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
        Schema record = Schema.createRecord(safeName(dataset.name()), dataset.meaning(), "org.pubsub.prototype.telemetry", false);
        List<Schema.Field> fields = new ArrayList<>();
        for (DatasetCatalog.Field field : dataset.fields()) {
            Schema primitive = switch (field.type()) {
                case STRING -> Schema.create(Schema.Type.STRING);
                case LONG -> Schema.create(Schema.Type.LONG);
                case DOUBLE -> Schema.create(Schema.Type.DOUBLE);
                case BOOLEAN -> Schema.create(Schema.Type.BOOLEAN);
            };
            Schema type = field.nullable() ? Schema.createUnion(List.of(Schema.create(Schema.Type.NULL), primitive)) : primitive;
            fields.add(new Schema.Field(field.name(), type, field.meaning(), field.nullable() ? Schema.Field.NULL_DEFAULT_VALUE : null));
        }
        record.setFields(fields);
        return record;
    }

    private static String safeName(String name) {
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return result.toString();
    }

    static void aggregate(Path resultsDir) throws IOException {
        if (!Files.isDirectory(resultsDir)) throw new IllegalArgumentException("Results directory does not exist: " + resultsDir);
        List<Path> runs;
        try (var paths = Files.walk(resultsDir, 3)) {
            runs = paths.filter(path -> Files.isDirectory(path.resolve("raw")))
                    .filter(path -> !path.startsWith(resultsDir.resolve("combined")))
                    .sorted().toList();
        }
        if (runs.isEmpty()) throw new IllegalArgumentException("No experiment runs found below " + resultsDir);
        System.err.println("[phase-0.9] Aggregating " + runs.size() + " run(s) below " + resultsDir);
        Path combined = resultsDir.resolve("combined");
        Path combinedRaw = combined.resolve("raw");
        Files.createDirectories(combinedRaw);
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path target = combinedRaw.resolve(dataset.name() + ".jsonl");
            try (var output = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (Path run : runs) {
                    Path source = run.resolve("raw").resolve(dataset.name() + ".jsonl");
                    if (!Files.exists(source)) throw new IllegalArgumentException("Missing required dataset: " + source);
                    try (var input = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                        input.transferTo(output);
                    }
                }
            }
        }
        for (Path run : runs) {
            if (needsNormalization(run)) normalizeRun(run);
            else System.err.println("[phase-0.9] Reusing normalized run " + run);
        }
        normalizeRun(combined);
        writeDictionary(resultsDir.resolve("data-dictionary.md"));
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("runCount", runs.size());
        manifest.set("runs", JSON.valueToTree(runs.stream().map(resultsDir::relativize).map(Path::toString).toList()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(combined.resolve("manifest.json").toFile(), manifest);
        System.err.println("[phase-0.9] Aggregation complete: " + combined);
    }

    private static boolean needsNormalization(Path runDir) throws IOException {
        Path raw = runDir.resolve("raw");
        Path parquet = runDir.resolve("parquet");
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path input = raw.resolve(dataset.name() + ".jsonl");
            Path output = parquet.resolve(dataset.name() + ".parquet");
            if (!Files.exists(input) || !Files.exists(output)
                    || Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(input)) < 0) return true;
        }
        return !Files.exists(runDir.resolve("derived/summary.json"))
                || !Files.exists(runDir.resolve("derived/summary.csv"));
    }

    static void writeDictionary(Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        StringBuilder text = new StringBuilder("# Phase 0.9 telemetry data dictionary\n\n")
                .append("Parquet is the canonical analysis format. JSONL files are the authoritative raw observations.\n\n");
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            text.append("## `").append(dataset.name()).append("`\n\n").append(dataset.meaning()).append(".\n\n")
                    .append("| Field | Type | Unit | Meaning | Nullable | Source component |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n");
            for (DatasetCatalog.Field field : dataset.fields()) {
                text.append("| `").append(field.name()).append("` | ").append(field.type().name().toLowerCase(Locale.ROOT))
                        .append(" | ").append(field.unit()).append(" | ").append(field.meaning()).append(" | ")
                        .append(field.nullable() ? "yes" : "no").append(" | ").append(field.source()).append(" |\n");
            }
            text.append('\n');
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path runDir) throws IOException {
        Path derived = runDir.resolve("derived");
        Files.createDirectories(derived);
        List<JsonNode> lifecycle = read(runDir.resolve("raw/event_lifecycle.jsonl"));
        List<JsonNode> messages = read(runDir.resolve("raw/message_transmissions.jsonl"));
        List<JsonNode> persistence = read(runDir.resolve("raw/persistence_operations.jsonl"));
        List<JsonNode> resources = read(runDir.resolve("raw/resource_samples.jsonl"));
        List<JsonNode> subscriptions = read(runDir.resolve("raw/subscriptions.jsonl"));
        List<JsonNode> overlays = read(runDir.resolve("raw/overlay_edges.jsonl"));
        List<JsonNode> cardano = read(runDir.resolve("raw/cardano_transactions.jsonl"));
        long published = lifecycle.stream().filter(x -> "PUBLISHED".equals(x.path("stage").asText())).map(x -> x.path("eventId").asText()).distinct().count();
        long accepted = lifecycle.stream().filter(x -> "ACCEPTED".equals(x.path("stage").asText()))
                .map(x -> x.path("eventId").asText() + "@" + x.path("nodeId").asText()).distinct().count();
        long duplicates = lifecycle.stream().filter(x -> "DUPLICATE".equals(x.path("stage").asText())).count();
        long transmissionBytes = messages.stream().mapToLong(x -> x.path("bytes").asLong()).sum();
        double meanCpu = resources.stream().filter(x -> x.hasNonNull("cpuPercent")).mapToDouble(x -> x.path("cpuPercent").asDouble()).average().orElse(0);
        double meanPersistence = persistence.stream().filter(x -> x.hasNonNull("durationMs")).mapToDouble(x -> x.path("durationMs").asDouble()).average().orElse(0);
        long subscriberCount = subscriptions.stream().filter(x -> x.path("subscribed").asBoolean())
                .map(x -> x.path("nodeId").asText()).distinct().count();
        double coverage = published == 0 || subscriberCount == 0 ? 0 : Math.min(1, (double) accepted / (published * subscriberCount));
        double p95Persistence = percentile(persistence.stream().filter(x -> x.hasNonNull("durationMs"))
                .mapToDouble(x -> x.path("durationMs").asDouble()).sorted().toArray(), 0.95);
        double meanCardano = cardano.stream().mapToDouble(x -> x.path("durationMs").asDouble()).average().orElse(0);
        long distinctEdges = overlays.stream().map(x -> x.path("layer").asText() + ':' + x.path("nodeId").asText()
                + ':' + x.path("peerNodeId").asText() + ':' + x.path("topicId").asText()).distinct().count();
        ObjectNode summary = JSON.createObjectNode();
        summary.put("publishedEventCount", published);
        summary.put("acceptedObservationCount", accepted);
        summary.put("deliveryHitRatio", coverage);
        summary.put("deliveryCoverage", coverage);
        summary.put("duplicateCount", duplicates);
        summary.put("messageCount", messages.size());
        summary.put("messageBytes", transmissionBytes);
        summary.put("messageOverheadPerPublishedEvent", published == 0 ? 0 : (double) messages.size() / published);
        summary.put("meanPersistenceLatencyMs", meanPersistence);
        summary.put("p95PersistenceLatencyMs", p95Persistence);
        summary.put("meanCpuPercent", meanCpu);
        summary.put("overlayDistinctEdgeCount", distinctEdges);
        summary.put("meanCardanoControlPlaneLatencyMs", meanCardano);
        JSON.writerWithDefaultPrettyPrinter().writeValue(derived.resolve("summary.json").toFile(), summary);
        String csv = "metric,value\n" +
                "publishedEventCount," + published + "\n" +
                "acceptedObservationCount," + accepted + "\n" +
                "deliveryHitRatio," + summary.path("deliveryHitRatio").asDouble() + "\n" +
                "duplicateCount," + duplicates + "\n" +
                "messageCount," + messages.size() + "\n" +
                "messageBytes," + transmissionBytes + "\n" +
                "meanPersistenceLatencyMs," + meanPersistence + "\n" +
                "p95PersistenceLatencyMs," + p95Persistence + "\n" +
                "meanCpuPercent," + meanCpu + "\n";
        Files.writeString(derived.resolve("summary.csv"), csv, StandardCharsets.UTF_8);
    }

    private static List<JsonNode> read(Path file) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) if (!line.isBlank()) values.add(JSON.readTree(line));
        }
        return values;
    }

    private static double percentile(double[] values, double percentile) {
        if (values.length == 0) return 0;
        int index = Math.min(values.length - 1, Math.max(0, (int) Math.ceil(percentile * values.length) - 1));
        return values[index];
    }
}
