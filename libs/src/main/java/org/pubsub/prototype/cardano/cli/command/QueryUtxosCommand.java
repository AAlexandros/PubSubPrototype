package org.pubsub.prototype.cardano.cli.command;

import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.util.List;

/** Queries all UTxOs at an address as JSON. */
public record QueryUtxosCommand(String address, String networkMagic) implements CardanoCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "query", "utxo",
                "--address", address,
                "--testnet-magic", networkMagic,
                "--output-json");
    }
}
