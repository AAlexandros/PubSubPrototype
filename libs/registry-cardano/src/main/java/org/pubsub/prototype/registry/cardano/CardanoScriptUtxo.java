package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.TopicState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CardanoScriptUtxo(
        String ref,
        Map<String, Long> assets,
        Optional<TopicState> topicState,
        String rawDatum
) {
    public CardanoScriptUtxo {
        Objects.requireNonNull(ref, "ref");
        assets = Map.copyOf(Objects.requireNonNull(assets, "assets"));
        topicState = Objects.requireNonNull(topicState, "topicState");
        rawDatum = rawDatum == null ? "" : rawDatum;
    }

    public boolean containsPolicy(String policyId) {
        return assets.keySet().stream().anyMatch(asset -> asset.equals(policyId) || asset.startsWith(policyId + "."));
    }
}
