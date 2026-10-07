package org.pubsub.prototype.cardano.cli.transaction;

import java.util.List;

/** Typed description of a Cardano CLI transaction command. */
public interface CardanoTransactionCommand {
    /** Returns arguments following the {@code cardano-cli} executable name. */
    List<String> arguments();

    /** Returns arguments in the form accepted by {@code CardanoCommandRunner}. */
    default String[] argumentArray() {
        return arguments().toArray(String[]::new);
    }
}
