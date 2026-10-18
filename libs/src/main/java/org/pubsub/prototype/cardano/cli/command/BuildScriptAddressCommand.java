package org.pubsub.prototype.cardano.cli.command;

import org.pubsub.prototype.cardano.cli.CardanoCommand;

import java.nio.file.Path;
import java.util.List;

/** Builds a testnet address whose payment credential is a script. */
public record BuildScriptAddressCommand(Path scriptFile, String networkMagic) implements CardanoCommand {
    @Override
    public List<String> arguments() {
        return List.of(
                "address", "build",
                "--payment-script-file", scriptFile.toString(),
                "--testnet-magic", networkMagic);
    }
}
