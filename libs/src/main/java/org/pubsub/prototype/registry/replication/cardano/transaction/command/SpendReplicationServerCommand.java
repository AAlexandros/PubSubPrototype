package org.pubsub.prototype.registry.replication.cardano.transaction.command;

import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionCommand;

import java.nio.file.Path;
import java.util.List;

/** Builds a transaction that updates or unregisters a replication-server registration UTxO. */
public record SpendReplicationServerCommand(
        String networkMagic,
        String changeAddress,
        String scriptInput,
        Path validatorScript,
        Path redeemerFile,
        String paymentInput,
        String collateralInput,
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
                "--tx-in", scriptInput,
                "--tx-in-script-file", validatorScript.toString(),
                "--tx-in-inline-datum-present",
                "--tx-in-redeemer-file", redeemerFile.toString(),
                "--tx-in", paymentInput,
                "--tx-in-collateral", collateralInput,
                "--tx-out", scriptOutput,
                "--tx-out-inline-datum-file", datumFile.toString(),
                "--required-signer-hash", requiredSignerHash,
                "--out-file", transactionBodyFile.toString());
    }
}
