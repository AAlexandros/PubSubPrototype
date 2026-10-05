package org.pubsub.prototype.registry.cardano.transaction;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;

/** Provides the configured local identity used to fund and sign registry transactions. */
final class CardanoTransactionSigner {
    private final CardanoRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private final CardanoRegistryIdentityValidator identityValidator;
    private CardanoIdentity identity;
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
    synchronized CardanoIdentity identity() {
        if (identity == null) {
            identity = CardanoIdentity.load(config);
        }
        return identity;
    }

    /** Derives and caches the configured signer's public payment-key hash. */
    synchronized String paymentKeyHash() {
        if (paymentKeyHash == null) {
            paymentKeyHash = identityValidator.requirePaymentKeyHash(identity().vkeyHash(commandRunner));
        }
        return paymentKeyHash;
    }
}
