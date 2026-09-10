package org.pubsub.prototype.telemetry;

import java.io.IOException;
import java.nio.file.Path;

public final class TelemetryTool {
    private static final String NORMALIZE_COMMAND = "normalize";
    private static final String AGGREGATE_COMMAND = "aggregate";
    private static final String DICTIONARY_COMMAND = "dictionary";

    private TelemetryTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        switch (args[0]) {
            case NORMALIZE_COMMAND -> normalizeRun(Path.of(args[1]));
            case AGGREGATE_COMMAND -> aggregate(Path.of(args[1]));
            case DICTIONARY_COMMAND -> writeDictionary(Path.of(args[1]));
            default -> usage();
        }
    }

    static void normalizeRun(Path runDir) throws IOException {
        RunNormalizer.normalize(runDir);
    }

    static void aggregate(Path resultsDir) throws IOException {
        RunAggregator.aggregate(resultsDir);
    }

    static void writeDictionary(Path output) throws IOException {
        DataDictionaryWriter.write(output);
    }

    private static void usage() {
        throw new IllegalArgumentException(
                "Usage: telemetry <normalize RUN_DIR | aggregate RESULTS_DIR | dictionary OUTPUT>");
    }
}
