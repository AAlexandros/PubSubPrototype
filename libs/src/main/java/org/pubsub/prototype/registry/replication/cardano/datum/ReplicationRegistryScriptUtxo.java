package org.pubsub.prototype.registry.replication.cardano.datum;

import org.pubsub.prototype.persistence.ReplicationServerState;

import java.util.Optional;

/** Script UTxO data needed to inspect and spend a replication-server registration. */
public record ReplicationRegistryScriptUtxo(String ref, long lovelace, Optional<ReplicationServerState> state) {
}
