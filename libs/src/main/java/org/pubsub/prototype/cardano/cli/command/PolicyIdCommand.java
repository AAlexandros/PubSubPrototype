package org.pubsub.prototype.cardano.cli.command;

import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.nio.file.Path;
import java.util.List;

/** Calculates a minting-policy ID from a script. */
public record PolicyIdCommand(Path scriptFile) implements CardanoCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "latest", "transaction", "policyid",
                "--script-file", scriptFile.toString());
    }
}
