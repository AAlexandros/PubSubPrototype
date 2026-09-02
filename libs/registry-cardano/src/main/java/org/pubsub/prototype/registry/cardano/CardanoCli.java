package org.pubsub.prototype.registry.cardano;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class CardanoCli {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private final CardanoRegistryConfig config;

    CardanoCli(CardanoRegistryConfig config) {
        this.config = config;
    }

    boolean available() {
        if (hostCardanoCliAvailable()) {
            return true;
        }
        return devnetDirIfPresent()
                .map(devnet -> Files.exists(devnet.resolve("compose.yaml")))
                .orElse(false);
    }

    private boolean hostCardanoCliAvailable() {
        try {
            Process process = new ProcessBuilder("cardano-cli", "--version").start();
            return process.waitFor() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    boolean canSubmitTransactions() {
        Map<String, String> env = readNetworkEnv();
        return available()
                && env.containsKey(CardanoRegistryNames.RuntimeKey.NETWORK_MAGIC.value())
                && env.containsKey(CardanoRegistryNames.RuntimeKey.NODE_SOCKET_PATH.value());
    }

    Optional<String> queryScriptUtxosJson(String validatorAddress) {
        Map<String, String> env = readNetworkEnv();
        String magic = env.get(CardanoRegistryNames.RuntimeKey.NETWORK_MAGIC.value());
        if (magic == null || magic.isBlank() || !available()) {
            return Optional.empty();
        }
        return Optional.of(run(
                "cardano-cli",
                "query",
                "utxo",
                "--address",
                validatorAddress,
                "--testnet-magic",
                magic,
                "--output-json"
        ));
    }

    Map<String, String> readNetworkEnv() {
        Map<String, String> env = new HashMap<>();
        Path file = config.networkEnvFile();
        if (!Files.exists(file)) {
            return env;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    env.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            return env;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Cardano network env", ex);
        }
    }

    String run(String... args) {
        String[] command = hostCardanoCliAvailable() ? args : dockerCommand(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(readNetworkEnv());
        try {
            Process process = builder.start();
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
            if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " timed out after " + COMMAND_TIMEOUT);
            }
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + stderr);
            }
            return stdout;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to run " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + String.join(" ", command), ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unable to read command output for " + String.join(" ", command), ex);
        }
    }

    private static String readStream(java.io.InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read process stream", ex);
        }
    }

    private String[] dockerCommand(String[] args) {
        if (args.length == 0 || !"cardano-cli".equals(args[0])) {
            return args;
        }
        Path devnetDir = devnetDirIfPresent()
                .orElseThrow(() -> new IllegalStateException("Unable to derive devnet directory for Docker cardano-cli"));
        String[] translated = new String[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            translated[i - 1] = translatePath(args[i], devnetDir);
        }
        String[] prefix = {
                "docker", "compose",
                "--env-file", devnetDir.resolve("versions.env").toString(),
                "-f", devnetDir.resolve("compose.yaml").toString(),
                "exec", "-T",
                "-e", CardanoRegistryNames.RuntimeKey.NODE_SOCKET_PATH.value() + "=/devnet/runtime/testnet/socket/node1/sock",
                "cardano-node",
                "cardano-cli"
        };
        String[] command = new String[prefix.length + translated.length];
        System.arraycopy(prefix, 0, command, 0, prefix.length);
        System.arraycopy(translated, 0, command, prefix.length, translated.length);
        return command;
    }

    private String translatePath(String value, Path devnetDir) {
        Path path;
        try {
            path = Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return value;
        }
        Path normalizedDevnet = devnetDir.toAbsolutePath().normalize();
        if (path.startsWith(normalizedDevnet)) {
            return "/devnet/" + normalizedDevnet.relativize(path).toString().replace('\\', '/');
        }
        return value;
    }

    private Path devnetDir() {
        return devnetDirIfPresent()
                .orElseThrow(() -> new IllegalStateException("Unable to derive devnet directory"));
    }

    private Optional<Path> devnetDirIfPresent() {
        Path runtime = config.runtimeDir().toAbsolutePath().normalize().getParent();
        if (runtime == null) {
            return Optional.empty();
        }
        Path devnet = runtime.getParent();
        if (devnet == null) {
            return Optional.empty();
        }
        return Optional.of(devnet);
    }
}
