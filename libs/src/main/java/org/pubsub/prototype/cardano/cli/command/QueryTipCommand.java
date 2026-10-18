package org.pubsub.prototype.cardano.cli.command;

import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.util.List;

/** Queries the current node tip as JSON. */
public record QueryTipCommand(String networkMagic) implements CardanoCommand {
    @Override
    public List<String> arguments() {
        return List.of("query", "tip", "--testnet-magic", networkMagic);
    }
}
