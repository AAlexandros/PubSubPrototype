package org.pubsub.prototype.registry.topic.cardano;

/** Environment names and values shared with Cardano registry tooling. */
public final class CardanoRegistryEnvironment {
    /** Deployed topic-registry validator address. */
    public static final String TOPIC_REGISTRY_VALIDATOR_ADDRESS = "TOPIC_REGISTRY_VALIDATOR_ADDRESS";
    /** Deployed topic minting-policy identifier. */
    public static final String TOPIC_POLICY_ID = "TOPIC_POLICY_ID";

    private CardanoRegistryEnvironment() {
    }
}
