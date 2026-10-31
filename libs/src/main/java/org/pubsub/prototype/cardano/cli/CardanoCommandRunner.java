package org.pubsub.prototype.cardano.cli;

import org.pubsub.prototype.cardano.cli.command.QueryUtxosCommand;

import java.util.Optional;

/** Executes Cardano CLI operations through the configured backend. */
public interface CardanoCommandRunner {
    /**
     * Returns the network magic used by Cardano commands.
     *
     * @throws IllegalStateException when network settings are unavailable
     */
    String networkMagic();

    /**
     * Executes a typed Cardano CLI command and returns its standard output.
     *
     * @throws IllegalStateException when the command cannot be executed or fails
     */
    String run(CardanoCommand command);

    /**
     * Queries UTxOs at a script address.
     * A successful query with no UTxOs returns {@code "{}"}.
     *
     * @throws IllegalStateException when an enabled live query cannot be executed or fails
     */
    default Optional<String> queryScriptUtxosJson(String validatorAddress) {
        return Optional.of(run(new QueryUtxosCommand(validatorAddress, networkMagic())));
    }

    /**
     * Verifies that this backend can execute Cardano CLI commands.
     */
    void requireCommandExecution();
}
