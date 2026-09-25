package org.pubsub.prototype.util;

/** Shared persistence model field names and limits. */
public final class PersistenceConstants {
    public static final String SERVER_ID_FIELD = SharedFieldNames.SERVER_ID;
    public static final String OPERATOR_FIELD = "operator";
    public static final String HOST_FIELD = SharedFieldNames.HOST;
    public static final String PORT_FIELD = SharedFieldNames.PORT;
    public static final String COMMITMENT_START_EPOCH_FIELD = "commitmentStartEpoch";
    public static final String COMMITMENT_END_EPOCH_FIELD = "commitmentEndEpoch";
    public static final String ACTIVE_FIELD = "active";

    public static final String EVENT_KEY_FIELD = SharedFieldNames.EVENT_KEY;
    public static final String TOPIC_ID_FIELD = SharedFieldNames.TOPIC_ID;
    public static final String PUBLISHER_KEY_ID_FIELD = SharedFieldNames.PUBLISHER_KEY_ID;
    public static final String SEQUENCE_NUMBER_FIELD = "sequenceNumber";
    public static final String RECORD_TYPE_FIELD = "recordType";
    public static final String RECORD_KEY_FIELD = "recordKey";
    public static final String MEMBERSHIP_VERSION_FIELD = "membershipVersion";
    public static final String RESPONSIBLE_SERVER_ID_FIELD = "responsibleServerId";
    public static final String UNAVAILABLE_SERVER_ID_FIELD = "unavailableServerId";
    public static final String EVENT_ENVELOPE_FIELD = "eventEnvelope";
    public static final String STORED_AT_FIELD = "storedAt";
    public static final String KEY_FIELD = "key";
    public static final String REPLICATION_FACTOR_FIELD = "replicationFactor";
    public static final String CBOR_HEX_FIELD = "cborHex";
    public static final String LAST_DELIVERED_SEQUENCE_NUMBER_FIELD = "lastDeliveredSequenceNumber";
    public static final String LAST_DELIVERED_TIMESTAMP_FIELD = "lastDeliveredTimestamp";
    public static final String LATEST_SEQUENCE_NUMBER_FIELD = "latestSequenceNumber";
    public static final String LATEST_TIMESTAMP_FIELD = "latestTimestamp";
    public static final String TOPIC_RETENTION_PERIOD_FIELD = "topicRetentionPeriod";
    public static final String STORED_AT_EPOCH_FIELD = "storedAtEpoch";
    public static final String EXPIRES_AFTER_EPOCH_FIELD = "expiresAfterEpoch";
    public static final String EVENTS_FIELD = "events";
    public static final String TOPIC_LOGS_FIELD = "topicLogs";
    public static final String CONCURRENCY_FIELD = "concurrency";
    public static final String EPOCH_LENGTH_MILLIS_FIELD = "epochLengthMillis";
    public static final String SINCE_TIMESTAMP_FIELD = "sinceTimestamp";
    public static final String RETRIES_FIELD = "retries";
    public static final String SURVIVING_REPLICAS_FIELD = "survivingReplicas";
    public static final String RESPONSIBLE_SERVER_IDS_FIELD = "responsibleServerIds";
    public static final String UNAVAILABLE_SERVER_IDS_FIELD = "unavailableServerIds";

    public static final String EVENTS_DIRECTORY = "events";
    public static final String TOPIC_LOGS_DIRECTORY = "topic-logs";
    public static final String METADATA_DIRECTORY = "metadata";
    public static final String SERVER_ID_FILE = "server-id.txt";
    public static final String TOPIC_INDEX_FILE = "topic-index.json";
    public static final String JSON_EXTENSION = ".json";
    public static final String JSON_EXTENSION_PATTERN = "\\.json$";

    public static final String MAINTENANCE_REASON = "maintenance";
    public static final String JOIN_REASON = "join";
    public static final String RECONCILIATION_REASON = "reconciliation";

    public static final int MIN_PORT = NetworkConstants.MIN_PORT;
    public static final int MAX_PORT = NetworkConstants.MAX_PORT;

    private PersistenceConstants() {
    }
}
