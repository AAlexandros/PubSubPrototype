package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.cardano.CardanoEnvironment;
import org.pubsub.prototype.cardano.CardanoNetwork;
import org.pubsub.prototype.cardano.cli.CacheOnlyCardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.HostCardanoCommandRunner;
import org.pubsub.prototype.cardano.devnet.DevnetCardanoCommandRunner;

final class CardanoCommandRunners {
    private CardanoCommandRunners() {
    }

    /**
     * Creates the command runner selected by the registry configuration.
     *
     * @param config validated registry configuration
     * @return the configured runner
     */
    static CardanoCommandRunner create(CardanoRegistryConfig config) {
        return switch (config.cliBackend()) {
            case CACHE_ONLY -> new CacheOnlyCardanoCommandRunner();
            case HOST -> host(config);
            case DEVNET -> devnet(config);
        };
    }

    /**
     * Creates a runner for a host-installed {@code cardano-cli} and host-visible node socket.
     *
     * @param config validated registry configuration
     * @return the host runner
     */
    private static CardanoCommandRunner host(CardanoRegistryConfig config) {
        CardanoNetwork network = config.requireNetwork(
                CardanoEnvironment.NETWORK_MAGIC,
                CardanoEnvironment.NODE_SOCKET_PATH
        );
        return new HostCardanoCommandRunner(network);
    }

    /**
     * Creates a runner for {@code cardano-cli} inside the local Docker Compose Cardano node.
     *
     * @param config validated registry configuration
     * @return the devnet runner
     */
    private static CardanoCommandRunner devnet(CardanoRegistryConfig config) {
        CardanoNetwork network = config.requireNetwork(
                CardanoEnvironment.NETWORK_MAGIC
        );
        return new DevnetCardanoCommandRunner(
                config.devnetDir(),
                network
        );
    }
}
