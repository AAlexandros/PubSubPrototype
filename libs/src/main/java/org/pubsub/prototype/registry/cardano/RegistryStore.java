package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.TopicState;

import java.util.List;
import java.util.Objects;

public record RegistryStore(String validatorAddress, String policyId, long nextSeed, List<TopicState> topics) {
    public RegistryStore {
        Objects.requireNonNull(validatorAddress, CardanoRegistryNames.StoreField.VALIDATOR_ADDRESS.value());
        Objects.requireNonNull(policyId, CardanoRegistryNames.StoreField.POLICY_ID.value());
        topics = List.copyOf(Objects.requireNonNull(topics, CardanoRegistryNames.StoreField.TOPICS.value()));
    }
}
