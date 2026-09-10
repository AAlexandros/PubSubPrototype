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
    private static final String STAGE_FIELD = "stage";
    private static final String PUBLISHED_STAGE = "PUBLISHED";
    private static final String ACCEPTED_STAGE = "ACCEPTED";
    private static final String DUPLICATE_STAGE = "DUPLICATE";

    static void write(Path runDir) throws IOException {
        Path derived = runDir.resolve(TelemetryLayout.DERIVED_DIRECTORY);
        Files.createDirectories(derived);
        List<JsonNode> lifecycle = read(TelemetryLayout.rawDataset(runDir, "event_lifecycle"));
        List<JsonNode> messages = read(TelemetryLayout.rawDataset(runDir, "message_transmissions"));
        List<JsonNode> persistence = read(TelemetryLayout.rawDataset(runDir, "persistence_operations"));
        List<JsonNode> resources = read(TelemetryLayout.rawDataset(runDir, "resource_samples"));
        List<JsonNode> subscriptions = read(TelemetryLayout.rawDataset(runDir, "subscriptions"));
        List<JsonNode> overlays = read(TelemetryLayout.rawDataset(runDir, "overlay_edges"));
        List<JsonNode> cardano = read(TelemetryLayout.rawDataset(runDir, "cardano_transactions"));
        long published = lifecycle.stream().filter(x -> PUBLISHED_STAGE.equals(x.path(STAGE_FIELD).asText()))
                .map(x -> x.path("eventId").asText()).distinct().count();
        long accepted = lifecycle.stream().filter(x -> ACCEPTED_STAGE.equals(x.path(STAGE_FIELD).asText()))
                .map(x -> x.path("eventId").asText() + "@" + x.path("nodeId").asText()).distinct().count();
        long duplicates = lifecycle.stream().filter(x -> DUPLICATE_STAGE.equals(x.path(STAGE_FIELD).asText())).count();
        long transmissionBytes = messages.stream().mapToLong(x -> x.path("bytes").asLong()).sum();
        double meanCpu = resources.stream().filter(x -> x.hasNonNull("cpuPercent"))
                .mapToDouble(x -> x.path("cpuPercent").asDouble()).average().orElse(0);
        double meanPersistence = persistence.stream().filter(x -> x.hasNonNull("durationMs"))
                .mapToDouble(x -> x.path("durationMs").asDouble()).average().orElse(0);
        long subscriberCount = subscriptions.stream().filter(x -> x.path("subscribed").asBoolean())
                .map(x -> x.path("nodeId").asText()).distinct().count();
        double coverage = published == 0 || subscriberCount == 0
                ? 0 : Math.min(1, (double) accepted / (published * subscriberCount));
        double p95Persistence = percentile(persistence.stream().filter(x -> x.hasNonNull("durationMs"))
                .mapToDouble(x -> x.path("durationMs").asDouble()).sorted().toArray(), 0.95);
        double meanCardano = cardano.stream().mapToDouble(x -> x.path("durationMs").asDouble()).average().orElse(0);
        long distinctEdges = overlays.stream().map(x -> x.path("layer").asText() + ':' + x.path("nodeId").asText()
                + ':' + x.path("peerNodeId").asText() + ':' + x.path("topicId").asText()).distinct().count();
        ObjectNode summary = TelemetryJson.MAPPER.createObjectNode();
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
        TelemetryJson.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(derived.resolve(TelemetryLayout.SUMMARY_JSON).toFile(), summary);
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
