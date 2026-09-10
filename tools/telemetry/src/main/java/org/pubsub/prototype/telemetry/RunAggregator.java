package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class RunAggregator {
    static void aggregate(Path resultsDir) throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            throw new IllegalArgumentException("Results directory does not exist: " + resultsDir);
        }
        List<Path> runs;
        try (var paths = Files.walk(resultsDir, 3)) {
            runs = paths.filter(path -> Files.isDirectory(path.resolve(TelemetryLayout.RAW_DIRECTORY)))
                    .filter(path -> !path.startsWith(resultsDir.resolve("combined")))
                    .sorted().toList();
        }
        if (runs.isEmpty()) throw new IllegalArgumentException("No experiment runs found below " + resultsDir);
        System.err.println("[phase-0.9] Aggregating " + runs.size() + " run(s) below " + resultsDir);
        Path combined = resultsDir.resolve("combined");
        Files.createDirectories(combined.resolve(TelemetryLayout.RAW_DIRECTORY));
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path target = TelemetryLayout.rawDataset(combined, dataset.name());
            try (var output = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (Path run : runs) {
                    Path source = TelemetryLayout.rawDataset(run, dataset.name());
                    if (!Files.exists(source)) throw new IllegalArgumentException("Missing required dataset: " + source);
                    try (var input = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                        input.transferTo(output);
                    }
                }
            }
        }
        for (Path run : runs) {
            if (needsNormalization(run)) RunNormalizer.normalize(run);
            else System.err.println("[phase-0.9] Reusing normalized run " + run);
        }
        RunNormalizer.normalize(combined);
        DataDictionaryWriter.write(resultsDir.resolve("data-dictionary.md"));
        ObjectNode manifest = TelemetryJson.MAPPER.createObjectNode();
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("runCount", runs.size());
        manifest.set("runs", TelemetryJson.MAPPER.valueToTree(
                runs.stream().map(resultsDir::relativize).map(Path::toString).toList()));
        TelemetryJson.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(combined.resolve("manifest.json").toFile(), manifest);
        System.err.println("[phase-0.9] Aggregation complete: " + combined);
    }

    private static boolean needsNormalization(Path runDir) throws IOException {
        for (DatasetCatalog.Dataset dataset : DatasetCatalog.ALL.values()) {
            Path input = TelemetryLayout.rawDataset(runDir, dataset.name());
            Path output = TelemetryLayout.parquetDataset(runDir, dataset.name());
            if (!Files.exists(input) || !Files.exists(output)
                    || Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(input)) < 0) return true;
        }
        Path derived = runDir.resolve(TelemetryLayout.DERIVED_DIRECTORY);
        return !Files.exists(derived.resolve(TelemetryLayout.SUMMARY_JSON))
                || !Files.exists(derived.resolve(TelemetryLayout.SUMMARY_CSV));
    }

    private RunAggregator() {
    }
}
