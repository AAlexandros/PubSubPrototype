package org.pubsub.prototype.cardano.cli;

import java.util.Optional;

/** Reads materialized state without allowing live Cardano commands. */
public final class CacheOnlyCardanoCommandRunner implements CardanoCommandRunner {
    @Override
    public String networkMagic() {
        throw commandsDisabled();
    }

    @Override
    public String run(String... arguments) {
        throw commandsDisabled();
    }

    @Override
    public Optional<String> queryScriptUtxosJson(String validatorAddress) {
        return Optional.empty();
    }

    @Override
    public void requireCommandExecution() {
        throw commandsDisabled();
    }

    private static CardanoCliUnavailableException commandsDisabled() {
        return new CardanoCliUnavailableException("Cardano CLI command execution is disabled");
    }
}
