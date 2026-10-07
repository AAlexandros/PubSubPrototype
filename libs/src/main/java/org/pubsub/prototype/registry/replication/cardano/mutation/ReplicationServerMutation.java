package org.pubsub.prototype.registry.replication.cardano.mutation;

/** Contract-specific action and replacement fields for a replication-server registration. */
public record ReplicationServerMutation(
        ReplicationServerOperation operation,
        String host,
        int port,
        long commitmentStartEpoch,
        long commitmentEndEpoch
) {
    public static ReplicationServerMutation update(String host, int port, long startEpoch, long endEpoch) {
        return new ReplicationServerMutation(ReplicationServerOperation.UPDATE_SERVER,
                host, port, startEpoch, endEpoch);
    }

    public static ReplicationServerMutation unregister() {
        return new ReplicationServerMutation(ReplicationServerOperation.UNREGISTER_SERVER, "", 0, 0, 0);
    }
}
