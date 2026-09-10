package org.pubsub.prototype.telemetry;

import java.nio.file.Path;

final class TelemetryLayout {
    static final String RAW_DIRECTORY = "raw";
    static final String PARQUET_DIRECTORY = "parquet";
    static final String DERIVED_DIRECTORY = "derived";
    static final String JSONL_EXTENSION = ".jsonl";
    static final String PARQUET_EXTENSION = ".parquet";
    static final String SUMMARY_JSON = "summary.json";
    static final String SUMMARY_CSV = "summary.csv";

    static Path rawDataset(Path runDir, String dataset) {
        return runDir.resolve(RAW_DIRECTORY).resolve(dataset + JSONL_EXTENSION);
    }

    static Path parquetDataset(Path runDir, String dataset) {
        return runDir.resolve(PARQUET_DIRECTORY).resolve(dataset + PARQUET_EXTENSION);
    }

    private TelemetryLayout() {
    }
}
