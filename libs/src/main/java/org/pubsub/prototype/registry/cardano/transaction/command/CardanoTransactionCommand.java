package org.pubsub.prototype.registry.cardano.transaction.command;

import java.util.List;

/** A typed description of one Cardano CLI transaction command. */
public interface CardanoTransactionCommand {
    /** Returns the arguments following the {@code cardano-cli} executable name. */
    List<String> arguments();

    /** Returns the command arguments in the form required by {@code CardanoCommandRunner}. */
    default String[] argumentArray() {
        return arguments().toArray(String[]::new);
    }
}
