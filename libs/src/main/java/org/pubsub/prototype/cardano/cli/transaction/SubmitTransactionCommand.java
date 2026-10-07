package org.pubsub.prototype.cardano.cli.transaction;

import java.nio.file.Path;
import java.util.List;

/** Submits a signed transaction to the configured Cardano network. */
public record SubmitTransactionCommand(Path signedTransactionFile, String networkMagic)
        implements CardanoTransactionCommand {
    @Override
    public List<String> arguments() {
        return List.of("latest", "transaction", "submit",
                "--tx-file", signedTransactionFile.toString(),
                "--testnet-magic", networkMagic);
    }
}
