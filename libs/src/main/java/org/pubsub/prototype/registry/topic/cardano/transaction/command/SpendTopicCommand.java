package org.pubsub.prototype.registry.topic.cardano.transaction.command;

import java.nio.file.Path;
import java.util.List;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionCommand;

/** Builds the transaction that spends a topic UTxO and produces its next state. */
public record SpendTopicCommand(
        String networkMagic,
        String changeAddress,
        String topicUtxo,
        Path validatorScript,
        Path redeemer,
        String paymentUtxo,
        String collateral,
        String topicOutput,
        Path datum,
        String requiredSignerHash,
        Path transactionBodyFile
) implements CardanoTransactionCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "latest", "transaction", "build",
                "--testnet-magic", networkMagic,
                "--change-address", changeAddress,
                "--tx-in", topicUtxo,
                "--tx-in-script-file", validatorScript.toString(),
                "--tx-in-inline-datum-present",
                "--tx-in-redeemer-file", redeemer.toString(),
                "--tx-in", paymentUtxo,
                "--tx-in-collateral", collateral,
                "--tx-out", topicOutput,
                "--tx-out-inline-datum-file", datum.toString(),
                "--required-signer-hash", requiredSignerHash,
                "--out-file", transactionBodyFile.toString()
        );
    }
}
