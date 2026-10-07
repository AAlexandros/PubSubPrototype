package org.pubsub.prototype.registry.replication.cardano.transaction.command;

import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionCommand;

import java.nio.file.Path;
import java.util.List;

/** Builds a transaction that creates a replication-server registration UTxO. */
public record CreateReplicationServerCommand(
        String networkMagic,
        String changeAddress,
        String paymentInput,
        String scriptOutput,
        Path datumFile,
        String requiredSignerHash,
        Path transactionBodyFile
) implements CardanoTransactionCommand {
    @Override
    public List<String> arguments() {
        return List.of("latest", "transaction", "build",
                "--testnet-magic", networkMagic,
                "--change-address", changeAddress,
                "--tx-in", paymentInput,
                "--tx-out", scriptOutput,
                "--tx-out-inline-datum-file", datumFile.toString(),
                "--required-signer-hash", requiredSignerHash,
                "--out-file", transactionBodyFile.toString());
    }
}
