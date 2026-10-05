package org.pubsub.prototype.registry.cardano.transaction.command;

import java.nio.file.Path;
import java.util.List;

/** Builds the transaction that mints and locks a new topic token at the registry validator. */
public record CreateTopicCommand(
        String networkMagic,
        String changeAddress,
        String creationSeed,
        String collateral,
        String topicOutput,
        Path datum,
        String topicAsset,
        Path mintingPolicy,
        Path mintRedeemer,
        String requiredSignerHash,
        Path transactionBodyFile
) implements CardanoTransactionCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "latest", "transaction", "build",
                "--testnet-magic", networkMagic,
                "--change-address", changeAddress,
                "--tx-in", creationSeed,
                "--tx-in-collateral", collateral,
                "--tx-out", topicOutput,
                "--tx-out-inline-datum-file", datum.toString(),
                "--mint", topicAsset,
                "--mint-script-file", mintingPolicy.toString(),
                "--mint-redeemer-file", mintRedeemer.toString(),
                "--required-signer-hash", requiredSignerHash,
                "--out-file", transactionBodyFile.toString()
        );
    }
}
