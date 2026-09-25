package org.pubsub.prototype.telemetry;

/** Stable telemetry dataset, field, stage, and derived-metric names. */
final class TelemetryConstants {
    static final class Dataset {
        static final String RUNS = "runs";
        static final String EVENT_LIFECYCLE = "event_lifecycle";
        static final String MESSAGE_TRANSMISSIONS = "message_transmissions";
        static final String OVERLAY_EDGES = "overlay_edges";
        static final String PROTOCOL_CYCLES = "protocol_cycles";
        static final String SUBSCRIPTIONS = "subscriptions";
        static final String PERSISTENCE_OPERATIONS = "persistence_operations";
        static final String REPLICA_STATE = "replica_state";
        static final String FAULTS = "faults";
        static final String RESOURCE_SAMPLES = "resource_samples";
        static final String CARDANO_TRANSACTIONS = "cardano_transactions";

        private Dataset() {
        }
    }

    static final class Field {
        static final String GENERATED_AT = "generatedAt";
        static final String RUN_COUNT = "runCount";
        static final String RUNS = "runs";
        static final String NODE_ID = "nodeId";
        static final String PEER_NODE_ID = "peerNodeId";
        static final String TOPIC_ID = "topicId";
        static final String EVENT_ID = "eventId";
        static final String STAGE = "stage";
        static final String BYTES = "bytes";
        static final String CPU_PERCENT = "cpuPercent";
        static final String DURATION_MS = "durationMs";
        static final String SUBSCRIBED = "subscribed";
        static final String LAYER = "layer";

        private Field() {
        }
    }

    static final class Layout {
        static final String COMBINED_DIRECTORY = "combined";
        static final String DATA_DICTIONARY_FILE = "data-dictionary.md";
        static final String MANIFEST_FILE = "manifest.json";

        private Layout() {
        }
    }

    static final class Stage {
        static final String PUBLISHED = "PUBLISHED";
        static final String ACCEPTED = "ACCEPTED";
        static final String DUPLICATE = "DUPLICATE";

        private Stage() {
        }
    }

    static final class Metric {
        static final String PUBLISHED_EVENT_COUNT = "publishedEventCount";
        static final String ACCEPTED_OBSERVATION_COUNT = "acceptedObservationCount";
        static final String DELIVERY_HIT_RATIO = "deliveryHitRatio";
        static final String DELIVERY_COVERAGE = "deliveryCoverage";
        static final String DUPLICATE_COUNT = "duplicateCount";
        static final String MESSAGE_COUNT = "messageCount";
        static final String MESSAGE_BYTES = "messageBytes";
        static final String MESSAGE_OVERHEAD_PER_PUBLISHED_EVENT = "messageOverheadPerPublishedEvent";
        static final String MEAN_PERSISTENCE_LATENCY_MS = "meanPersistenceLatencyMs";
        static final String P95_PERSISTENCE_LATENCY_MS = "p95PersistenceLatencyMs";
        static final String MEAN_CPU_PERCENT = "meanCpuPercent";
        static final String OVERLAY_DISTINCT_EDGE_COUNT = "overlayDistinctEdgeCount";
        static final String MEAN_CARDANO_CONTROL_PLANE_LATENCY_MS = "meanCardanoControlPlaneLatencyMs";

        private Metric() {
        }
    }

    private TelemetryConstants() {
    }
}
