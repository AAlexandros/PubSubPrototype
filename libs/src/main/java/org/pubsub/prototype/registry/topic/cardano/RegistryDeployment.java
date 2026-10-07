package org.pubsub.prototype.registry.topic.cardano;

/** Identifiers produced by deploying the Cardano registry scripts. */
public record RegistryDeployment(String validatorAddress, String policyId) {
}
