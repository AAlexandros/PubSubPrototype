package org.pubsub.prototype.persistence;

import static org.pubsub.prototype.util.PersistenceConstants.COMMITMENT_END_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.COMMITMENT_START_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.HOST_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.OPERATOR_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.PORT_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNegative;
import static org.pubsub.prototype.util.Validators.requirePort;

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
        serverId = PersistenceHex.require256(serverId, SERVER_ID_FIELD);
        operator = requireNonBlank(operator, OPERATOR_FIELD);
        host = requireNonBlank(host, HOST_FIELD);
        requirePort(port, PORT_FIELD);
        requireNonNegative(commitmentStartEpoch, COMMITMENT_START_EPOCH_FIELD);
        require(commitmentEndEpoch >= commitmentStartEpoch,
                "invalid " + COMMITMENT_START_EPOCH_FIELD + " and " + COMMITMENT_END_EPOCH_FIELD + " range");
    }

    public ReplicationServer member() {
        return new ReplicationServer(serverId, host, port);
    }

    public ReplicationServerState deactivate() {
        return new ReplicationServerState(serverId, operator, host, port,
                commitmentStartEpoch, commitmentEndEpoch, false);
    }
}
