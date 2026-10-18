package org.pubsub.prototype.cardano.cli.command;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CardanoCommandTest {
    @Test
    void buildsTipQuery() {
        assertArguments(new QueryTipCommand("42"),
                "query", "tip", "--testnet-magic", "42");
    }

    @Test
    void buildsUtxoQuery() {
        assertArguments(new QueryUtxosCommand("addr_test1", "42"),
                "query", "utxo", "--address", "addr_test1",
                "--testnet-magic", "42", "--output-json");
    }

    @Test
    void buildsPaymentKeyHash() {
        assertArguments(new PaymentKeyHashCommand(Path.of("payment.vkey")),
                "address", "key-hash", "--payment-verification-key-file", "payment.vkey");
    }

    @Test
    void buildsScriptAddress() {
        assertArguments(new BuildScriptAddressCommand(Path.of("validator.plutus.json"), "42"),
                "address", "build", "--payment-script-file", "validator.plutus.json",
                "--testnet-magic", "42");
    }

    @Test
    void buildsPolicyId() {
        assertArguments(new PolicyIdCommand(Path.of("policy.plutus.json")),
                "latest", "transaction", "policyid", "--script-file", "policy.plutus.json");
    }

    private static void assertArguments(CardanoCommand command, String... expected) {
        assertArrayEquals(expected, command.argumentArray());
    }
}
