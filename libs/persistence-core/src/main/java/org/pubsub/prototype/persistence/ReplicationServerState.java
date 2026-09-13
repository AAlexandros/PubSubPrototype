package org.pubsub.prototype.persistence;

import java.util.Objects;

public record ReplicationServerState(
        String serverId,
        String operator,
        String host,
        int port,
        long commitmentStartEpoch,
        long commitmentEndEpoch,
        boolean active
) {
    public ReplicationServerState {
        serverId = PersistenceHex.require256(serverId, "serverId");
        operator = Objects.requireNonNull(operator, "operator");
        host = Objects.requireNonNull(host, "host");
        if (operator.isBlank() || host.isBlank()) throw new IllegalArgumentException("operator and host are required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        if (commitmentStartEpoch < 0 || commitmentEndEpoch < commitmentStartEpoch) {
            throw new IllegalArgumentException("invalid commitment epoch range");
        }
    }

    public ReplicationServer member() {
        return new ReplicationServer(serverId, host, port);
    }

    public ReplicationServerState deactivate() {
        return new ReplicationServerState(serverId, operator, host, port,
                commitmentStartEpoch, commitmentEndEpoch, false);
    }
}
