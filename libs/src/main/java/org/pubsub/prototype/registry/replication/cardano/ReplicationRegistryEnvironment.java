package org.pubsub.prototype.registry.replication.cardano;

/** Environment keys persisted for the deployed replication registry. */
public final class ReplicationRegistryEnvironment {
    public static final String VALIDATOR_ADDRESS = "REPLICATION_REGISTRY_VALIDATOR_ADDRESS";
    public static final String SCRIPT_FILE = "REPLICATION_REGISTRY_SCRIPT";
    public static final String STATE_FILE = "REPLICATION_REGISTRY_STATE";

    private ReplicationRegistryEnvironment() {
    }
}
