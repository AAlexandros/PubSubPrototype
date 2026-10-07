package org.pubsub.prototype.registry.replication.cardano.mutation;

/** On-chain transitions supported by the replication registry validator. */
public enum ReplicationServerOperation {
    UPDATE_SERVER,
    UNREGISTER_SERVER
}
