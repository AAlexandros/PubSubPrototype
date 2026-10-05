package org.pubsub.prototype.cardano.cli;

import java.util.Optional;

/** Executes Cardano CLI operations through the configured backend. */
public interface CardanoCommandRunner {
    /**
     * Returns the network magic used by Cardano commands.
     *
     * @throws CardanoCliUnavailableException when this backend has no live Cardano access
     */
    String networkMagic();

    /**
     * Executes a Cardano CLI command and returns its standard output.
     * Arguments start after the {@code cardano-cli} executable name.
     *
     * @throws CardanoCliUnavailableException when command execution is disabled
     * @throws IllegalStateException when the command cannot be executed or fails
     */
    String run(String... arguments);

    /**
     * Queries UTxOs at a script address.
     * An empty result means live querying is disabled; a successful query with no UTxOs returns {@code "{}"}.
     *
     * @throws IllegalStateException when an enabled live query cannot be executed or fails
     */
    default Optional<String> queryScriptUtxosJson(String validatorAddress) {
        return Optional.of(run(
                "query",
                "utxo",
                "--address",
                validatorAddress,
                "--testnet-magic",
                networkMagic(),
                "--output-json"
        ));
    }

    /**
     * Verifies that this backend can execute Cardano CLI commands.
     *
     * @throws CardanoCliUnavailableException when command execution is disabled
     */
    void requireCommandExecution();
}
