package org.pubsub.prototype.registry.cardano.transaction;

import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.cardano.mutation.TopicMutation;
import org.pubsub.prototype.util.CryptoConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validates and normalizes the public key identifiers stored in the Cardano registry. */
final class CardanoRegistryIdentityValidator {
    private static final String PAYMENT_KEY_HASH_PATTERN = "[0-9a-fA-F]{56}";

    /** Validates and normalizes the key identifier carried by a topic mutation for on-chain storage. */
    TopicMutation normalizeMutation(TopicMutation mutation) {
        return switch (mutation.operation()) {
            case ADD_OWNER, REMOVE_OWNER, ADD_ADMIN, REMOVE_ADMIN ->
                    new TopicMutation(mutation.operation(), requirePaymentKeyHash(mutation.value()));
            case ADD_PUBLISHER, REMOVE_PUBLISHER ->
                    new TopicMutation(mutation.operation(), normalizePublisherKeyId(mutation.value()));
            case DELETE_TOPIC, SET_REPLICATION_FACTOR, SET_RETENTION_PERIOD -> mutation;
        };
    }

    /** Validates and normalizes owner or administrator payment-key hashes. */
    List<String> requirePaymentKeyHashes(List<String> actors) {
        List<String> hashes = new ArrayList<>();
        for (String actor : actors) {
            hashes.add(requirePaymentKeyHash(actor));
        }
        return List.copyOf(hashes);
    }

    /** Validates a public owner or administrator identifier and returns its canonical lowercase form. */
    String requirePaymentKeyHash(String actor) {
        if (!actor.matches(PAYMENT_KEY_HASH_PATTERN)) {
            throw new RegistryConflictException(
                    "owner or administrator must be a 56-character Cardano payment-key hash: " + actor
            );
        }
        return actor.toLowerCase(Locale.ROOT);
    }

    /** Validates and normalizes publisher event-key identifiers for storage on-chain. */
    List<String> normalizePublisherKeyIds(List<String> publishers) {
        List<String> values = new ArrayList<>();
        for (String publisher : publishers) {
            values.add(normalizePublisherKeyId(publisher));
        }
        return List.copyOf(values);
    }

    private static String normalizePublisherKeyId(String publisher) {
        if (!publisher.matches(CryptoConstants.SHA_256_HEX_CASE_INSENSITIVE_PATTERN)) {
            throw new RegistryConflictException("publisher must be a 256-bit event key ID: " + publisher);
        }
        return publisher.toLowerCase(Locale.ROOT);
    }
}
