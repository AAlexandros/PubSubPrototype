package org.pubsub.prototype.cardano.devnet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Maps host devnet files to their paths in the Docker container.
 * Commands connect to pool node 1, the single pool node created by the current devnet configuration.
 */
final class DevnetLayout {
    private static final String COMPOSE_FILE = "compose.yaml";
    private static final String VERSIONS_FILE = "versions.env";
    private static final String HOST_DIRECTORY_FIELD = "hostDirectory";
    private static final String CONTAINER_ROOT = "/devnet";
    private static final String CONTAINER_SOCKET = "/devnet/runtime/testnet/socket/node1/sock";
    private static final String SOCKET_ENVIRONMENT = "CARDANO_NODE_SOCKET_PATH=" + CONTAINER_SOCKET;

    private final Path hostDirectory;

    DevnetLayout(Path hostDirectory) {
        this.hostDirectory = Objects.requireNonNull(hostDirectory, HOST_DIRECTORY_FIELD)
                .toAbsolutePath()
                .normalize();
        requireFile(composeFile());
        requireFile(versionsFile());
    }

    Path composeFile() {
        return hostDirectory.resolve(COMPOSE_FILE);
    }

    Path versionsFile() {
        return hostDirectory.resolve(VERSIONS_FILE);
    }

    String socketEnvironment() {
        return SOCKET_ENVIRONMENT;
    }

    String containerPath(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (path.startsWith(hostDirectory)) {
                String relative = hostDirectory.relativize(path).toString().replace('\\', '/');
                return CONTAINER_ROOT + "/" + relative;
            }
        } catch (RuntimeException ignored) {
            // Command flags and non-path values are passed through unchanged.
        }
        return value;
    }

    private static void requireFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Devnet file does not exist: " + file);
        }
    }
}
