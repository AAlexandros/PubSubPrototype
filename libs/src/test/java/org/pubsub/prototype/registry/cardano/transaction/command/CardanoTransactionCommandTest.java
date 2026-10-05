package org.pubsub.prototype.registry.cardano.transaction.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CardanoTransactionCommandTest {
    private static final Path BODY = Path.of("transaction.txbody");

    @Test
    void rendersTopicCreationCommand() {
        CardanoTransactionCommand command = new CreateTopicCommand(
                "42", "addr_test1", "seed#0", "collateral#1", "validator+2000000+1 policy.token",
                Path.of("topic.datum.json"), "1 policy.token", Path.of("policy.plutus.json"),
                Path.of("mint.redeemer.json"), "signer-hash", BODY
        );

        assertArrayEquals(new String[]{
                "latest", "transaction", "build",
                "--testnet-magic", "42",
                "--change-address", "addr_test1",
                "--tx-in", "seed#0",
                "--tx-in-collateral", "collateral#1",
                "--tx-out", "validator+2000000+1 policy.token",
                "--tx-out-inline-datum-file", "topic.datum.json",
                "--mint", "1 policy.token",
                "--mint-script-file", "policy.plutus.json",
                "--mint-redeemer-file", "mint.redeemer.json",
                "--required-signer-hash", "signer-hash",
                "--out-file", "transaction.txbody"
        }, command.argumentArray());
    }

    @Test
    void rendersTopicSpendCommand() {
        CardanoTransactionCommand command = new SpendTopicCommand(
                "42", "addr_test1", "topic#0", Path.of("validator.plutus.json"), Path.of("mutation.redeemer.json"),
                "payment#1", "collateral#2", "validator+2000000+1 policy.token", Path.of("next.datum.json"),
                "signer-hash", BODY
        );

        assertArrayEquals(new String[]{
                "latest", "transaction", "build",
                "--testnet-magic", "42",
                "--change-address", "addr_test1",
                "--tx-in", "topic#0",
                "--tx-in-script-file", "validator.plutus.json",
                "--tx-in-inline-datum-present",
                "--tx-in-redeemer-file", "mutation.redeemer.json",
                "--tx-in", "payment#1",
                "--tx-in-collateral", "collateral#2",
                "--tx-out", "validator+2000000+1 policy.token",
                "--tx-out-inline-datum-file", "next.datum.json",
                "--required-signer-hash", "signer-hash",
                "--out-file", "transaction.txbody"
        }, command.argumentArray());
    }

    @Test
    void rendersSigningAndSubmissionCommands() {
        assertArrayEquals(new String[]{
                "latest", "transaction", "sign",
                "--tx-body-file", "transaction.txbody",
                "--signing-key-file", "payment.skey",
                "--testnet-magic", "42",
                "--out-file", "transaction.tx"
        }, new SignTransactionCommand(BODY, Path.of("payment.skey"), "42", Path.of("transaction.tx")).argumentArray());
        assertArrayEquals(new String[]{
                "latest", "transaction", "submit",
                "--tx-file", "transaction.tx",
                "--testnet-magic", "42"
        }, new SubmitTransactionCommand(Path.of("transaction.tx"), "42").argumentArray());
    }
}
