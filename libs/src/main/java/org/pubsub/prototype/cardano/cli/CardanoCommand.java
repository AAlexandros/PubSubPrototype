package org.pubsub.prototype.cardano.cli;

import java.util.List;

/** Typed description of arguments passed to {@code cardano-cli}. */
public interface CardanoCommand {
    /** Returns arguments following the {@code cardano-cli} executable name. */
    List<String> arguments();

    /** Returns arguments in the form used by process-oriented integrations and tests. */
    default String[] argumentArray() {
        return arguments().toArray(String[]::new);
    }
}
