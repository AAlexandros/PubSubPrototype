package org.pubsub.prototype.persistence;

import java.util.List;

public record MaintenanceStatus(
        String serverId,
        String membershipVersion,
        List<String> suspectedPeers,
        List<String> confirmedUnavailablePeers,
        List<ReplicaRepairStatus> repairQueue,
        List<String> underReplicatedRecords
) {
    public MaintenanceStatus {
        serverId = PersistenceHex.require256(serverId, "serverId");
        suspectedPeers = List.copyOf(suspectedPeers);
        confirmedUnavailablePeers = List.copyOf(confirmedUnavailablePeers);
        repairQueue = List.copyOf(repairQueue);
        underReplicatedRecords = List.copyOf(underReplicatedRecords);
    }
}
