package org.pubsub.prototype.cardano.cli;

import org.pubsub.prototype.cardano.CardanoEnvironment;
import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.CardanoNetwork;
import org.pubsub.prototype.cardano.devnet.DevnetCardanoCommandRunner;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates a Cardano CLI runner for the selected execution backend.
 *
 * <p>The {@code HOST} backend runs {@code cardano-cli} installed on the current
 * machine. It uses the process environment when both network magic and node
 * socket path are set; otherwise it reads them from {@code network.env} next
 * to the contract runtime directory.</p>
 *
 * <p>The {@code DEVNET} backend runs {@code cardano-cli} through Docker Compose
 * using the local devnet files and the network magic from {@code network.env}.
 * Both backends expect the runtime directory to follow the project layout
 * {@code <devnet>/runtime/<contract>/}.</p>
 */
public final class CardanoCommandRunnerFactory {
    private CardanoCommandRunnerFactory() {
    }

    /**
     * Creates the runner for the requested backend and contract runtime directory.
     *
     * @param backend where Cardano CLI commands should run
     * @param runtimeDir contract runtime directory, conventionally
     *                   {@code <devnet>/runtime/<contract>/}
     * @return a runner configured for that backend and network
     * @throws IllegalStateException if {@code network.env} cannot be read
     * @throws IllegalArgumentException if required network settings are absent
     *                                   or the devnet directory cannot be derived
     */
    public static CardanoCommandRunner create(CardanoCliBackend backend, Path runtimeDir) {
        return switch (backend) {
            case HOST -> new HostCardanoCommandRunner(hostNetwork(runtimeDir));
            case DEVNET -> new DevnetCardanoCommandRunner(devnetDir(runtimeDir), network(runtimeDir, false));
        };
    }

    /**
     * Resolves HOST network settings, preferring the process environment when
     * it contains both {@link CardanoEnvironment#NETWORK_MAGIC} and
     * {@link CardanoEnvironment#NODE_SOCKET_PATH}. The optional CLI timeout is
     * passed through in that case as well. If either required setting is absent,
     * both settings are loaded from {@code network.env} beside the runtime
     * directory.
     */
    private static CardanoNetwork hostNetwork(Path runtimeDir) {
        Map<String, String> process = System.getenv();
        if (process.containsKey(CardanoEnvironment.NETWORK_MAGIC)
                && process.containsKey(CardanoEnvironment.NODE_SOCKET_PATH)) {
            Map<String, String> values = new HashMap<>();
            values.put(CardanoEnvironment.NETWORK_MAGIC, process.get(CardanoEnvironment.NETWORK_MAGIC));
            values.put(CardanoEnvironment.NODE_SOCKET_PATH, process.get(CardanoEnvironment.NODE_SOCKET_PATH));
            if (process.containsKey(CardanoEnvironment.CLI_TIMEOUT_MS)) {
                values.put(CardanoEnvironment.CLI_TIMEOUT_MS, process.get(CardanoEnvironment.CLI_TIMEOUT_MS));
            }
            return new CardanoNetwork(process.get(CardanoEnvironment.NETWORK_MAGIC), values);
        }
        return network(runtimeDir, true);
    }

    /**
     * Reads network settings from {@code network.env} beside the contract
     * runtime directory.
     *
     * @param runtimeDir contract runtime directory
     * @param requireSocket whether the file must also define a node socket path
     * @return the validated network magic and environment values
     */
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

    /**
     * Resolves the devnet root from {@code <devnet>/runtime/<contract>/}.
     *
     * @param runtimeDir contract runtime directory
     * @return the devnet root directory
     * @throws IllegalArgumentException if the runtime path does not have the
     *                                  expected directory depth
     */
    private static Path devnetDir(Path runtimeDir) {
        Path parent = runtimeDir.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getParent() == null) {
            throw new IllegalArgumentException("Unable to derive devnet directory from " + runtimeDir);
        }
        return parent.getParent();
    }
}
