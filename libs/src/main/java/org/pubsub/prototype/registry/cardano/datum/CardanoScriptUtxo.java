package org.pubsub.prototype.registry.cardano.datum;

import org.pubsub.prototype.registry.TopicState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Cardano UTxO parsed from {@code cardano-cli} output.
 *
 * @param ref transaction output reference in {@code transactionHash#index} form.
 * The transactionHash is the hash/ID of the transaction that created the output.
 * The ID of the current transaction, is the hash of its transaction body.
 * @param assets asset quantities held by the output
 * @param topicState decoded topic datum, or empty when no valid topic datum is present
 * @param rawDatum original inline datum JSON, when present
 */
public record CardanoScriptUtxo(
        String ref,
        Map<String, Long> assets,
        Optional<TopicState> topicState,
        Optional<String> rawDatum
) {
    private static final String REF_FIELD = "ref";
    private static final String ASSETS_FIELD = "assets";
    private static final String TOPIC_STATE_FIELD = "topicState";
    private static final String RAW_DATUM_FIELD = "rawDatum";

    public CardanoScriptUtxo {
        Objects.requireNonNull(ref, REF_FIELD);
        assets = Map.copyOf(Objects.requireNonNull(assets, ASSETS_FIELD));
        topicState = Objects.requireNonNull(topicState, TOPIC_STATE_FIELD);
        rawDatum = Objects.requireNonNull(rawDatum, RAW_DATUM_FIELD);
    }

    /** Returns whether the output contains an asset minted by the given policy. */
    public boolean containsPolicy(String policyId) {
        return assets.keySet().stream().anyMatch(asset -> asset.equals(policyId) || asset.startsWith(policyId + "."));
    }
}
