package org.pubsub.prototype.persistence.maintenance;

import org.pubsub.prototype.util.HexUtils;

import java.util.List;

import static org.pubsub.prototype.constants.PersistenceConstants.SERVER_ID_FIELD;

public record MaintenanceStatus(
        String serverId,
        String membershipVersion,
        List<String> suspectedPeers,
        List<String> confirmedUnavailablePeers,
        List<ReplicaRepairStatus> repairQueue,
        List<String> underReplicatedRecords
) {
    public MaintenanceStatus {
        serverId = HexUtils.normalizeSha256(serverId, SERVER_ID_FIELD);
        suspectedPeers = List.copyOf(suspectedPeers);
        confirmedUnavailablePeers = List.copyOf(confirmedUnavailablePeers);
        repairQueue = List.copyOf(repairQueue);
        underReplicatedRecords = List.copyOf(underReplicatedRecords);
    }
}
