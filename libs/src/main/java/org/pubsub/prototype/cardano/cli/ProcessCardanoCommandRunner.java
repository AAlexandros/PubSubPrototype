package org.pubsub.prototype.cardano.cli;

import org.pubsub.prototype.cardano.CardanoNetwork;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Base for Cardano backends that execute an operating-system process. */
public abstract class ProcessCardanoCommandRunner implements CardanoCommandRunner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final CardanoNetwork network;

    /**
     * Creates a process runner using the supplied network environment.
     *
     * @param network validated network settings
     */
    protected ProcessCardanoCommandRunner(CardanoNetwork network) {
        this.network = network;
    }

    @Override
    public final String networkMagic() {
        return network.magic();
    }

    @Override
    public final void requireCommandExecution() {
        // Process-backed runners support Cardano commands.
    }

    @Override
    public final String run(String... arguments) {
        List<String> command = command(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(network.environment());
        try {
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " timed out after " + COMMAND_TIMEOUT);
            }
            String output = stdout.get();
            String error = stderr.get();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + error);
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to run " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + String.join(" ", command), ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unable to read command output for " + String.join(" ", command), ex);
        }
    }

    /**
     * Builds the full operating-system command for Cardano CLI arguments.
     *
     * @param arguments arguments following the {@code cardano-cli} executable
     * @return full process command
     */
    protected abstract List<String> command(String[] arguments);

    private static String read(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read process stream", ex);
        }
    }
}
