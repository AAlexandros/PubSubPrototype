package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SummaryWriter {
    static void write(Path runDir) throws IOException {
        Path derived = runDir.resolve(TelemetryLayout.DERIVED_DIRECTORY);
        Files.createDirectories(derived);
        List<JsonNode> lifecycle = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.EVENT_LIFECYCLE));
        List<JsonNode> messages = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.MESSAGE_TRANSMISSIONS));
        List<JsonNode> persistence = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.PERSISTENCE_OPERATIONS));
        List<JsonNode> resources = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.RESOURCE_SAMPLES));
        List<JsonNode> subscriptions = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.SUBSCRIPTIONS));
        List<JsonNode> overlays = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.OVERLAY_EDGES));
        List<JsonNode> cardano = read(TelemetryLayout.rawDataset(runDir, TelemetryConstants.Dataset.CARDANO_TRANSACTIONS));
        long published = lifecycle.stream().filter(x -> TelemetryConstants.Stage.PUBLISHED.equals(x.path(TelemetryConstants.Field.STAGE).asText()))
                .map(x -> x.path(TelemetryConstants.Field.EVENT_ID).asText()).distinct().count();
        long accepted = lifecycle.stream().filter(x -> TelemetryConstants.Stage.ACCEPTED.equals(x.path(TelemetryConstants.Field.STAGE).asText()))
                .map(x -> x.path(TelemetryConstants.Field.EVENT_ID).asText() + "@" + x.path(TelemetryConstants.Field.NODE_ID).asText()).distinct().count();
        long duplicates = lifecycle.stream().filter(x -> TelemetryConstants.Stage.DUPLICATE.equals(x.path(TelemetryConstants.Field.STAGE).asText())).count();
        long transmissionBytes = messages.stream().mapToLong(x -> x.path(TelemetryConstants.Field.BYTES).asLong()).sum();
        double meanCpu = resources.stream().filter(x -> x.hasNonNull(TelemetryConstants.Field.CPU_PERCENT))
                .mapToDouble(x -> x.path(TelemetryConstants.Field.CPU_PERCENT).asDouble()).average().orElse(0);
        double meanPersistence = persistence.stream().filter(x -> x.hasNonNull(TelemetryConstants.Field.DURATION_MS))
                .mapToDouble(x -> x.path(TelemetryConstants.Field.DURATION_MS).asDouble()).average().orElse(0);
        long subscriberCount = subscriptions.stream().filter(x -> x.path(TelemetryConstants.Field.SUBSCRIBED).asBoolean())
                .map(x -> x.path(TelemetryConstants.Field.NODE_ID).asText()).distinct().count();
        double coverage = published == 0 || subscriberCount == 0
                ? 0 : Math.min(1, (double) accepted / (published * subscriberCount));
        double p95Persistence = percentile(persistence.stream().filter(x -> x.hasNonNull(TelemetryConstants.Field.DURATION_MS))
                .mapToDouble(x -> x.path(TelemetryConstants.Field.DURATION_MS).asDouble()).sorted().toArray(), 0.95);
        double meanCardano = cardano.stream().mapToDouble(x -> x.path(TelemetryConstants.Field.DURATION_MS).asDouble()).average().orElse(0);
        long distinctEdges = overlays.stream().map(x -> x.path(TelemetryConstants.Field.LAYER).asText() + ':' + x.path(TelemetryConstants.Field.NODE_ID).asText()
                + ':' + x.path(TelemetryConstants.Field.PEER_NODE_ID).asText() + ':' + x.path(TelemetryConstants.Field.TOPIC_ID).asText()).distinct().count();
        ObjectNode summary = TelemetryJson.MAPPER.createObjectNode();
        summary.put(TelemetryConstants.Metric.PUBLISHED_EVENT_COUNT, published);
        summary.put(TelemetryConstants.Metric.ACCEPTED_OBSERVATION_COUNT, accepted);
        summary.put(TelemetryConstants.Metric.DELIVERY_HIT_RATIO, coverage);
        summary.put(TelemetryConstants.Metric.DELIVERY_COVERAGE, coverage);
        summary.put(TelemetryConstants.Metric.DUPLICATE_COUNT, duplicates);
        summary.put(TelemetryConstants.Metric.MESSAGE_COUNT, messages.size());
        summary.put(TelemetryConstants.Metric.MESSAGE_BYTES, transmissionBytes);
        summary.put(TelemetryConstants.Metric.MESSAGE_OVERHEAD_PER_PUBLISHED_EVENT, published == 0 ? 0 : (double) messages.size() / published);
        summary.put(TelemetryConstants.Metric.MEAN_PERSISTENCE_LATENCY_MS, meanPersistence);
        summary.put(TelemetryConstants.Metric.P95_PERSISTENCE_LATENCY_MS, p95Persistence);
        summary.put(TelemetryConstants.Metric.MEAN_CPU_PERCENT, meanCpu);
        summary.put(TelemetryConstants.Metric.OVERLAY_DISTINCT_EDGE_COUNT, distinctEdges);
        summary.put(TelemetryConstants.Metric.MEAN_CARDANO_CONTROL_PLANE_LATENCY_MS, meanCardano);
        TelemetryJson.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(derived.resolve(TelemetryLayout.SUMMARY_JSON).toFile(), summary);
        String csv = "metric,value\n" +
                TelemetryConstants.Metric.PUBLISHED_EVENT_COUNT + ',' + published + "\n" +
                TelemetryConstants.Metric.ACCEPTED_OBSERVATION_COUNT + ',' + accepted + "\n" +
                TelemetryConstants.Metric.DELIVERY_HIT_RATIO + ',' + summary.path(TelemetryConstants.Metric.DELIVERY_HIT_RATIO).asDouble() + "\n" +
                TelemetryConstants.Metric.DUPLICATE_COUNT + ',' + duplicates + "\n" +
                TelemetryConstants.Metric.MESSAGE_COUNT + ',' + messages.size() + "\n" +
                TelemetryConstants.Metric.MESSAGE_BYTES + ',' + transmissionBytes + "\n" +
                TelemetryConstants.Metric.MEAN_PERSISTENCE_LATENCY_MS + ',' + meanPersistence + "\n" +
                TelemetryConstants.Metric.P95_PERSISTENCE_LATENCY_MS + ',' + p95Persistence + "\n" +
                TelemetryConstants.Metric.MEAN_CPU_PERCENT + ',' + meanCpu + "\n";
        Files.writeString(derived.resolve(TelemetryLayout.SUMMARY_CSV), csv, StandardCharsets.UTF_8);
    }

    private static List<JsonNode> read(Path file) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.isBlank()) values.add(TelemetryJson.MAPPER.readTree(line));
            }
        }
        return values;
    }

    private static double percentile(double[] values, double percentile) {
        if (values.length == 0) return 0;
        int index = Math.min(values.length - 1,
                Math.max(0, (int) Math.ceil(percentile * values.length) - 1));
        return values[index];
    }

    private SummaryWriter() {
    }
}
