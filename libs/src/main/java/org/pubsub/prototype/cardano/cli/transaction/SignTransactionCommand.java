package org.pubsub.prototype.cardano.cli.transaction;

import java.nio.file.Path;
import java.util.List;

/** Signs a previously built transaction body. */
public record SignTransactionCommand(
        Path transactionBodyFile,
        Path signingKey,
        String networkMagic,
        Path signedTransactionFile
) implements CardanoTransactionCommand {
    @Override
    public List<String> arguments() {
        return List.of("latest", "transaction", "sign",
                "--tx-body-file", transactionBodyFile.toString(),
                "--signing-key-file", signingKey.toString(),
                "--testnet-magic", networkMagic,
                "--out-file", signedTransactionFile.toString());
    }
}
