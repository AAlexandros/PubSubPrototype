package org.pubsub.prototype.cardano.cli.command;

import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.nio.file.Path;
import java.util.List;

/** Derives the payment-key hash from a verification-key file. */
public record PaymentKeyHashCommand(Path verificationKeyFile) implements CardanoCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "address", "key-hash",
                "--payment-verification-key-file", verificationKeyFile.toString());
    }
}
