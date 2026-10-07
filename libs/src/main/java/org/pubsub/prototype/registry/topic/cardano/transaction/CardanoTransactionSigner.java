package org.pubsub.prototype.registry.topic.cardano.transaction;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;

import java.nio.file.Path;

/** Provides the configured local identity used to fund and sign registry transactions. */
final class CardanoTransactionSigner {
    private final CardanoRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private final CardanoRegistryIdentityValidator identityValidator;
    private CardanoPaymentIdentity identity;
    private String paymentKeyHash;

    CardanoTransactionSigner(
            CardanoRegistryConfig config,
            CardanoCommandRunner commandRunner,
            CardanoRegistryIdentityValidator identityValidator
    ) {
        this.config = config;
        this.commandRunner = commandRunner;
        this.identityValidator = identityValidator;
    }

    /** Returns the configured local signing identity, loading it lazily. */
    synchronized CardanoPaymentIdentity identity() {
        if (identity == null) {
            Path keysDirectory = config.runtimeDir().toAbsolutePath().getParent().getParent().resolve("keys");
            identity = CardanoPaymentIdentity.load(keysDirectory, config.signer());
        }
        return identity;
    }

    /** Derives and caches the configured signer's public payment-key hash. */
    synchronized String paymentKeyHash() {
        if (paymentKeyHash == null) {
            paymentKeyHash = identityValidator.requirePaymentKeyHash(identity().paymentKeyHash(commandRunner));
        }
        return paymentKeyHash;
    }
}
