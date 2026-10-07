package org.pubsub.prototype.cardano.cli;

import org.pubsub.prototype.cardano.CardanoEnvironment;
import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.CardanoNetwork;
import org.pubsub.prototype.cardano.devnet.DevnetCardanoCommandRunner;

import java.nio.file.Path;
import java.util.Map;

/** Creates the configured host, devnet, or cache-only Cardano CLI runner. */
public final class CardanoCommandRunnerFactory {
    private CardanoCommandRunnerFactory() {
    }

    /**
     * Creates a runner using the conventional runtime layout:
     * {@code <devnet>/runtime/<contract>/} with {@code network.env} under {@code runtime/}.
     */
    public static CardanoCommandRunner create(CardanoCliBackend backend, Path runtimeDir) {
        return switch (backend) {
            case CACHE_ONLY -> new CacheOnlyCardanoCommandRunner();
            case HOST -> new HostCardanoCommandRunner(network(runtimeDir, true));
            case DEVNET -> new DevnetCardanoCommandRunner(devnetDir(runtimeDir), network(runtimeDir, false));
        };
    }

    private static CardanoNetwork network(Path runtimeDir, boolean requireSocket) {
        Path parent = runtimeDir.toAbsolutePath().normalize().getParent();
        Path environmentFile = parent == null
                ? runtimeDir.resolve(CardanoEnvironment.NETWORK_FILE)
                : parent.resolve(CardanoEnvironment.NETWORK_FILE);
        String[] required = requireSocket
                ? new String[]{CardanoEnvironment.NETWORK_MAGIC, CardanoEnvironment.NODE_SOCKET_PATH}
                : new String[]{CardanoEnvironment.NETWORK_MAGIC};
        Map<String, String> values = CardanoEnvironmentFile.readRequired(environmentFile, required);
        return new CardanoNetwork(values.get(CardanoEnvironment.NETWORK_MAGIC), values);
    }

    private static Path devnetDir(Path runtimeDir) {
        Path parent = runtimeDir.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getParent() == null) {
            throw new IllegalArgumentException("Unable to derive devnet directory from " + runtimeDir);
        }
        return parent.getParent();
    }
}
