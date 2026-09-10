package org.pubsub.prototype.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DatasetCatalog {
    enum Type { STRING, LONG, DOUBLE, BOOLEAN }
    record Field(String name, Type type, String unit, String meaning, boolean nullable, String source) {}
    record Dataset(String name, String meaning, List<Field> fields) {}

    private static Field f(String name, Type type, String unit, String meaning, boolean nullable, String source) {
        return new Field(name, type, unit, meaning, nullable, source);
    }
    private static List<Field> observations(Field... fields) {
        var result = new java.util.ArrayList<Field>(List.of(
                f("runId", Type.STRING, "identifier", "Unique experiment run identifier", false, "experiment-controller"),
                f("scenarioId", Type.STRING, "identifier", "Scenario family identifier", false, "experiment-controller"),
                f("repetition", Type.LONG, "count", "Scenario repetition index", false, "experiment-controller"),
                f("randomSeed", Type.LONG, "seed", "Deterministic run seed", false, "experiment-controller"),
                f("timestampUtc", Type.STRING, "ISO-8601 UTC", "Wall-clock observation time", false, "observing component"),
                f("monotonicTimeNs", Type.LONG, "nanoseconds", "Process monotonic observation time", false, "observing component")
        ));
        result.addAll(List.of(fields));
        return List.copyOf(result);
    }

    static final Map<String, Dataset> ALL;
    static {
        Map<String, Dataset> all = new LinkedHashMap<>();
        add(all, "runs", "One record per experiment run", List.of(
                f("runId", Type.STRING, "identifier", "Unique run identifier", false, "experiment-controller"),
                f("scenarioId", Type.STRING, "identifier", "Scenario identifier", false, "scenario"),
                f("repetition", Type.LONG, "count", "Repetition index", false, "scenario"),
                f("randomSeed", Type.LONG, "seed", "Deterministic run seed", false, "scenario"),
                f("gitCommit", Type.STRING, "git hash", "Source revision", false, "experiment-controller"),
                f("javaVersion", Type.STRING, "version", "Java runtime version", false, "experiment-controller"),
                f("startTime", Type.STRING, "ISO-8601 UTC", "Run start", false, "experiment-controller"),
                f("endTime", Type.STRING, "ISO-8601 UTC", "Run end", true, "experiment-controller"),
                f("protocolConfiguration", Type.STRING, "JSON", "Protocol settings", false, "scenario"),
                f("workloadConfiguration", Type.STRING, "JSON", "Workload settings", false, "scenario"),
                f("telemetryConfiguration", Type.STRING, "JSON", "Telemetry settings", false, "scenario"),
                f("status", Type.STRING, "enum", "RUNNING, PASSED, or FAILED", false, "experiment-controller")
        ));
        add(all, "event_lifecycle", "Event stage observations", observations(
                f("nodeId", Type.STRING, "identifier", "Observing node", true, "pubsub-node"),
                f("topicId", Type.STRING, "identifier", "Topic", false, "pubsub-node"),
                f("eventId", Type.STRING, "identifier", "Event content identifier", false, "pubsub-node"),
                f("eventKey", Type.STRING, "identifier", "Persistence DHT key", true, "pubsub-node"),
                f("publisherKeyId", Type.STRING, "identifier", "Publisher signing-key identifier", true, "pubsub-node"),
                f("sequenceNumber", Type.LONG, "count", "Publisher sequence number", true, "pubsub-node"),
                f("peerNodeId", Type.STRING, "identifier", "Related peer", true, "pubsub-node"),
                f("stage", Type.STRING, "enum", "Lifecycle stage", false, "pubsub-node"),
                f("reason", Type.STRING, "text", "Rejection or duplicate detail", true, "pubsub-node")
        ));
        add(all, "message_transmissions", "Actual protocol message sends", observations(
                f("fromNodeId", Type.STRING, "identifier", "Sender", false, "transport/persistence client"),
                f("toNodeId", Type.STRING, "identifier", "Receiver", false, "transport/persistence client"),
                f("protocolLayer", Type.STRING, "enum", "TRANSPORT, SECURECYCLON, NAVIGATION, DISSEMINATION, or PERSISTENCE", false, "runtime"),
                f("messageType", Type.STRING, "enum", "Wire/API message type", false, "runtime"),
                f("topicId", Type.STRING, "identifier", "Topic when applicable", true, "runtime"),
                f("eventId", Type.STRING, "identifier", "Event when applicable", true, "runtime"),
                f("bytes", Type.LONG, "bytes", "Serialized transmission size", false, "runtime"),
                f("success", Type.BOOLEAN, "boolean", "Transmission completed successfully", false, "runtime")
        ));
        add(all, "overlay_edges", "Logical overlay snapshots", observations(
                f("layer", Type.STRING, "enum", "SECURECYCLON, NAVIGATION, or DISSEMINATION", false, "experiment-controller"),
                f("nodeId", Type.STRING, "identifier", "Local endpoint", false, "runtime API"),
                f("peerNodeId", Type.STRING, "identifier", "Remote endpoint", false, "runtime API"),
                f("topicId", Type.STRING, "identifier", "Topic for topic-specific overlays", true, "runtime API"),
                f("role", Type.STRING, "enum", "Edge role", true, "runtime API"),
                f("freshness", Type.LONG, "milliseconds", "Reported edge age", true, "runtime API")
        ));
        add(all, "protocol_cycles", "Protocol and maintenance cycle observations", observations(
                f("componentId", Type.STRING, "identifier", "Node or server running the cycle", false, "runtime"),
                f("protocol", Type.STRING, "enum", "SECURECYCLON, NAVIGATION, DISSEMINATION, or REPLICA_MAINTENANCE", false, "runtime"),
                f("cycleNumber", Type.LONG, "count", "Monotonic cycle index", false, "experiment-controller"),
                f("durationMs", Type.DOUBLE, "milliseconds", "Cycle observation/operation duration", true, "experiment-controller"),
                f("viewSize", Type.LONG, "count", "Observed view size", true, "runtime API"),
                f("success", Type.BOOLEAN, "boolean", "Cycle/API sample succeeded", false, "experiment-controller")
        ));
        add(all, "subscriptions", "Subscription changes and effective state", observations(
                f("nodeId", Type.STRING, "identifier", "Subscriber node", false, "pubsub-node API"),
                f("topicId", Type.STRING, "identifier", "Topic", false, "pubsub-node API"),
                f("action", Type.STRING, "enum", "SUBSCRIBE, UNSUBSCRIBE, or SNAPSHOT", false, "experiment-controller"),
                f("subscribed", Type.BOOLEAN, "boolean", "Effective state", false, "pubsub-node API")
        ));
        add(all, "persistence_operations", "Persistence and recovery operations", observations(
                f("nodeId", Type.STRING, "identifier", "Initiating node", true, "pubsub-node"),
                f("serverId", Type.STRING, "identifier", "Serving replication server", true, "replication-server"),
                f("operation", Type.STRING, "enum", "STORE, LOOKUP, RECOVERY_LOOKUP, REPAIR, RELEASE, or TOPIC_LOG_UPDATE", false, "runtime"),
                f("topicId", Type.STRING, "identifier", "Topic", true, "runtime"),
                f("eventId", Type.STRING, "identifier", "Event", true, "runtime"),
                f("eventKey", Type.STRING, "identifier", "Persistence key", true, "runtime"),
                f("durationMs", Type.DOUBLE, "milliseconds", "Operation latency", true, "runtime"),
                f("success", Type.BOOLEAN, "boolean", "Operation succeeded", false, "runtime"),
                f("recordCount", Type.LONG, "count", "Records affected or recovered", true, "runtime")
        ));
        add(all, "replica_state", "Replica ownership and responsibility snapshots", observations(
                f("serverId", Type.STRING, "identifier", "Server holding/reporting the record", false, "replication-server API"),
                f("recordType", Type.STRING, "enum", "EVENT or TOPIC_LOG", false, "replication-server API"),
                f("eventKey", Type.STRING, "identifier", "Event persistence key", true, "replication-server API"),
                f("topicId", Type.STRING, "identifier", "Topic", true, "replication-server API"),
                f("present", Type.BOOLEAN, "boolean", "Record is locally present", false, "replication-server API"),
                f("responsible", Type.BOOLEAN, "boolean", "Server belongs to current responsible set", true, "experiment-controller"),
                f("membershipVersion", Type.STRING, "hash", "Replication membership fingerprint", true, "replication-server API")
        ));
        add(all, "faults", "Explicit fault injections and recoveries", observations(
                f("targetId", Type.STRING, "identifier", "Affected node/server", false, "experiment-controller"),
                f("faultType", Type.STRING, "enum", "STOP, START, RESTART, MEMBERSHIP_CHANGE, or REPLICATION_FACTOR_CHANGE", false, "scenario"),
                f("action", Type.STRING, "enum", "INJECTED or CLEARED", false, "experiment-controller"),
                f("details", Type.STRING, "JSON/text", "Fault parameters", true, "scenario")
        ));
        add(all, "resource_samples", "Process/container resource observations", observations(
                f("componentId", Type.STRING, "identifier", "Sampled node/server", false, "experiment-controller"),
                f("cpuPercent", Type.DOUBLE, "percent", "CPU utilization", true, "container runtime"),
                f("rssBytes", Type.LONG, "bytes", "Resident memory", true, "container runtime"),
                f("heapUsedBytes", Type.LONG, "bytes", "JVM heap in use", true, "JVM/runtime"),
                f("networkRxBytes", Type.LONG, "bytes", "Cumulative received bytes", true, "container runtime"),
                f("networkTxBytes", Type.LONG, "bytes", "Cumulative transmitted bytes", true, "container runtime"),
                f("diskReadBytes", Type.LONG, "bytes", "Cumulative disk reads", true, "container runtime"),
                f("diskWriteBytes", Type.LONG, "bytes", "Cumulative disk writes", true, "container runtime"),
                f("storedEventCount", Type.LONG, "count", "Stored events", true, "replication-server API"),
                f("storedEventBytes", Type.LONG, "bytes", "Stored event payload estimate", true, "replication-server API"),
                f("openConnections", Type.LONG, "count", "Active peer connections", true, "runtime API")
        ));
        add(all, "cardano_transactions", "Local registry control-plane transactions", observations(
                f("operation", Type.STRING, "enum", "Registry operation", false, "experiment-controller"),
                f("registry", Type.STRING, "enum", "TOPIC or REPLICATION", false, "experiment-controller"),
                f("transactionId", Type.STRING, "identifier", "Transaction hash when reported", true, "cardano-cli"),
                f("topicId", Type.STRING, "identifier", "Affected topic", true, "registry CLI"),
                f("serverId", Type.STRING, "identifier", "Affected replication server", true, "registry CLI"),
                f("durationMs", Type.DOUBLE, "milliseconds", "CLI transaction latency", false, "experiment-controller"),
                f("success", Type.BOOLEAN, "boolean", "Transaction confirmed", false, "experiment-controller"),
                f("observationDelayMs", Type.DOUBLE, "milliseconds", "Delay until runtime observation", true, "experiment-controller")
        ));
        ALL = Map.copyOf(all);
    }

    private static void add(Map<String, Dataset> all, String name, String meaning, List<Field> fields) {
        all.put(name, new Dataset(name, meaning, fields));
    }

    static Dataset require(String name) {
        Dataset dataset = ALL.get(name);
        if (dataset == null) throw new IllegalArgumentException("Unknown telemetry dataset: " + name);
        return dataset;
    }

    private DatasetCatalog() {}
}
