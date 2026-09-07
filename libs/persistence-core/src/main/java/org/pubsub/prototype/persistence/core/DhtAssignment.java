package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.ReplicationServer;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public final class DhtAssignment {
    private static final BigInteger RING_SIZE = BigInteger.ONE.shiftLeft(256);

    private DhtAssignment() {
    }

    /** Shortest numeric distance in the unsigned 256-bit ring. */
    public static List<ReplicationServer> responsibleServers(String key, List<ReplicationServer> membership,
                                                              int replicationFactor) {
        BigInteger numericKey = unsigned(key, "key");
        if (replicationFactor <= 0) throw new IllegalArgumentException("replicationFactor must be positive");
        var unique = new LinkedHashMap<String, ReplicationServer>();
        membership.forEach(server -> unique.merge(server.serverId(), server, (left, right) -> {
            if (!left.equals(right)) throw new IllegalArgumentException("conflicting endpoints for serverId " + left.serverId());
            return left;
        }));
        return unique.values().stream()
                .sorted(Comparator
                        .comparing((ReplicationServer server) -> distance(numericKey, unsigned(server.serverId(), "serverId")))
                        .thenComparing(ReplicationServer::serverId))
                .limit(Math.min(replicationFactor, unique.size()))
                .toList();
    }

    public static BigInteger distance(String key, String serverId) {
        return distance(unsigned(key, "key"), unsigned(serverId, "serverId"));
    }

    private static BigInteger distance(BigInteger key, BigInteger server) {
        BigInteger direct = server.subtract(key).abs();
        return direct.min(RING_SIZE.subtract(direct));
    }

    private static BigInteger unsigned(String value, String field) {
        return new BigInteger(1, PersistenceHex.decode256(value, field));
    }
}
