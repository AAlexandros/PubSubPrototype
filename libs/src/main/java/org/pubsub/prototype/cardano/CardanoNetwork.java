package org.pubsub.prototype.cardano;

import java.util.Map;

import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

/**
 * Validated network values supplied to a Cardano command runner.
 *
 * @param magic Cardano network identifier used by CLI commands
 * @param environment environment variables supplied to the CLI process
 */
public record CardanoNetwork(String magic, Map<String, String> environment) {
    private static final String ENVIRONMENT_FIELD = "environment";

    public CardanoNetwork {
        magic = requireNonBlank(magic, CardanoEnvironment.NETWORK_MAGIC);
        environment = Map.copyOf(requireNonNull(
                environment,
                ENVIRONMENT_FIELD
        ));
    }
}
