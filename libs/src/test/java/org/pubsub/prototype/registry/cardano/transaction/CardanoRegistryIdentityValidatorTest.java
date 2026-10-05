package org.pubsub.prototype.registry.cardano.transaction;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.cardano.mutation.TopicMutation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardanoRegistryIdentityValidatorTest {
    private final CardanoRegistryIdentityValidator validator = new CardanoRegistryIdentityValidator();

    @Test
    void normalizesCanonicalPaymentKeyHashes() {
        String uppercaseHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01";

        assertEquals(uppercaseHash.toLowerCase(), validator.requirePaymentKeyHash(uppercaseHash));
        assertEquals(
                List.of(uppercaseHash.toLowerCase()),
                validator.requirePaymentKeyHashes(List.of(uppercaseHash))
        );
    }

    @Test
    void rejectsNamedIdentitiesForOwnersAndAdministrators() {
        RegistryConflictException exception = assertThrows(
                RegistryConflictException.class,
                () -> validator.requirePaymentKeyHash("node-2")
        );

        assertEquals(
                "owner or administrator must be a 56-character Cardano payment-key hash: node-2",
                exception.getMessage()
        );
    }

    @Test
    void normalizesPublisherKeyIds() {
        String uppercaseKeyId = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";

        assertEquals(List.of(uppercaseKeyId.toLowerCase()), validator.normalizePublisherKeyIds(List.of(uppercaseKeyId)));
    }

    @Test
    void rejectsInvalidPublisherKeyIds() {
        RegistryConflictException exception = assertThrows(
                RegistryConflictException.class,
                () -> validator.normalizePublisherKeyIds(List.of("publisher-2"))
        );

        assertEquals("publisher must be a 256-bit event key ID: publisher-2", exception.getMessage());
    }

    @Test
    void normalizesIdentityValuesAccordingToTheMutationOperation() {
        String uppercasePaymentHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01";
        String uppercasePublisherKeyId = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";

        assertEquals(
                TopicMutation.addOwner(uppercasePaymentHash.toLowerCase()),
                validator.normalizeMutation(TopicMutation.addOwner(uppercasePaymentHash))
        );
        assertEquals(
                TopicMutation.addPublisher(uppercasePublisherKeyId.toLowerCase()),
                validator.normalizeMutation(TopicMutation.addPublisher(uppercasePublisherKeyId))
        );
    }

    @Test
    void leavesMutationsWithoutIdentityValuesUntouched() {
        TopicMutation mutation = TopicMutation.setRetentionPeriod(60);

        assertSame(mutation, validator.normalizeMutation(mutation));
    }
}
