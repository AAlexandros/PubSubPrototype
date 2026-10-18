package org.pubsub.prototype.cardano.cli;

import org.pubsub.prototype.cardano.CardanoNetwork;

import java.util.ArrayList;
import java.util.List;

/** Runs the host-installed {@code cardano-cli}. */
public final class HostCardanoCommandRunner extends ProcessCardanoCommandRunner {
    private static final String EXECUTABLE = "cardano-cli";

    /**
     * Creates a runner using the supplied network environment.
     *
     * @param network validated network settings
     */
    public HostCardanoCommandRunner(CardanoNetwork network) {
        super(network);
    }

    @Override
    protected List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(EXECUTABLE);
        command.addAll(arguments);
        return command;
    }
}
