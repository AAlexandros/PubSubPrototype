package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.ReplicationServer;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import static org.pubsub.prototype.util.CryptoConstants.SHA_256_BITS;
import static org.pubsub.prototype.util.PersistenceConstants.KEY_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.REPLICATION_FACTOR_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requirePositive;

public final class DhtAssignment {
    private static final BigInteger RING_SIZE = BigInteger.ONE.shiftLeft(SHA_256_BITS);

    private DhtAssignment() {
    }

    /** Shortest numeric distance in the unsigned 256-bit ring. */
    public static List<ReplicationServer> responsibleServers(String key, List<ReplicationServer> membership,
                                                              int replicationFactor) {
        BigInteger numericKey = unsigned(key, KEY_FIELD);
        requirePositive(replicationFactor, REPLICATION_FACTOR_FIELD);
        var unique = new LinkedHashMap<String, ReplicationServer>();
        membership.forEach(server -> unique.merge(server.serverId(), server, (left, right) -> {
            require(left.equals(right), "conflicting endpoints for serverId " + left.serverId());
            return left;
        }));
        return unique.values().stream()
                .sorted(Comparator
                        .comparing((ReplicationServer server) ->
                                distance(numericKey, unsigned(server.serverId(), SERVER_ID_FIELD)))
                        .thenComparing(ReplicationServer::serverId))
                .limit(Math.min(replicationFactor, unique.size()))
                .toList();
    }

    public static BigInteger distance(String key, String serverId) {
        return distance(unsigned(key, KEY_FIELD), unsigned(serverId, SERVER_ID_FIELD));
    }

    private static BigInteger distance(BigInteger key, BigInteger server) {
        BigInteger direct = server.subtract(key).abs();
        return direct.min(RING_SIZE.subtract(direct));
    }

    private static BigInteger unsigned(String value, String field) {
        return new BigInteger(1, PersistenceHex.decode256(value, field));
    }
}
