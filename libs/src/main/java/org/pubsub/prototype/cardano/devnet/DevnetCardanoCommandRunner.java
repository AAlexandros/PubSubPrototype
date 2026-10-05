package org.pubsub.prototype.cardano.devnet;

import org.pubsub.prototype.cardano.CardanoNetwork;
import org.pubsub.prototype.cardano.cli.ProcessCardanoCommandRunner;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class DevnetCardanoCommandRunner extends ProcessCardanoCommandRunner {
    private final DevnetLayout layout;

    public DevnetCardanoCommandRunner(Path devnetDir, CardanoNetwork network) {
        super(network);
        this.layout = new DevnetLayout(devnetDir);
    }

    @Override
    protected List<String> command(String[] arguments) {
        List<String> prefix = List.of(
                "docker", "compose",                              // Run through Docker Compose.
                "--env-file", layout.versionsFile().toString(),    // Load the pinned image versions.
                "-f", layout.composeFile().toString(),             // Select the devnet definition.
                "exec", "-T",                                     // Execute without an interactive terminal.
                "-e", layout.socketEnvironment(),                  // Use pool node 1's container socket.
                "cardano-node", "cardano-cli"                      // Run the CLI in the Cardano service.
        );
        return Stream.concat(
                prefix.stream(),
                Arrays.stream(arguments).map(layout::containerPath)
        ).toList();
    }
}
