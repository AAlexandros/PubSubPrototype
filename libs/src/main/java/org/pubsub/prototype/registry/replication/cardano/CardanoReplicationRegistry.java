package org.pubsub.prototype.registry.replication.cardano;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.persistence.ReplicationRegistryClient;
import org.pubsub.prototype.persistence.ReplicationRegistryReader;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryScriptUtxo;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryUtxoParser;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerMutation;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerOperation;
import org.pubsub.prototype.registry.replication.cardano.transaction.ReplicationRegistryTransactionBuilder;
import org.pubsub.prototype.util.JsonSupport;
import org.pubsub.prototype.util.TextFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Cardano-backed replication registry client and observed-state snapshot. */
public final class CardanoReplicationRegistry implements ReplicationRegistryClient, ReplicationRegistryReader {
    private static final TypeReference<List<ReplicationServerState>> STATES = new TypeReference<>() { };

    private final CardanoReplicationRegistryConfig config;
    private final CardanoCommandRunner runner;
    private final CardanoReplicationRegistryDeploymentManager deployments;
    private final ReplicationRegistryUtxoParser utxoParser = new ReplicationRegistryUtxoParser();
    private ReplicationRegistryTransactionBuilder transactions;

    public CardanoReplicationRegistry(CardanoReplicationRegistryConfig config) {
        this(config, CardanoCommandRunnerFactory.create(config.cliBackend(), config.runtimeDir()));
    }

    public CardanoReplicationRegistry(CardanoReplicationRegistryConfig config, CardanoCommandRunner runner) {
        this.config = config;
        this.runner = runner;
        this.deployments = new CardanoReplicationRegistryDeploymentManager(config, runner);
    }

    public ReplicationRegistryDeployment deploy() {
        return deployments.initialize();
    }

    public String configuredServerId() {
        return transactions().serverId();
    }

    public String configuredOperatorId() {
        return transactions().paymentKeyHash();
    }

    @Override
    public synchronized List<ReplicationServerState> queryServers(boolean includeInactive) {
        List<ReplicationServerState> states = observedStates();
        return states.stream().filter(state -> includeInactive || state.active())
                .sorted(Comparator.comparing(ReplicationServerState::serverId)).toList();
    }

    @Override
    public ReplicationServerState registerServer(ReplicationServerState server) {
        runner.requireCommandExecution();
        requireConfiguredIdentity(server);
        Optional<ReplicationRegistryScriptUtxo> existing = findUtxo(server.serverId());
        if (existing.isEmpty()) {
            transactions().create(server);
        } else {
            ReplicationRegistryScriptUtxo currentUtxo = existing.orElseThrow();
            ReplicationServerState current = currentUtxo.state().orElseThrow();
            if (!current.operator().equals(server.operator())) {
                throw new IllegalArgumentException("serverId is already controlled by another Cardano account");
            }
            if (!current.active()) {
                throw new IllegalArgumentException("an inactive permanent serverId cannot be re-registered");
            }
            transactions().spend(currentUtxo, server,
                    ReplicationServerMutation.update(server.host(), server.port(),
                            server.commitmentStartEpoch(), server.commitmentEndEpoch()));
        }
        awaitState(server);
        return server;
    }

    @Override
    public void unregisterServer(String serverId) {
        runner.requireCommandExecution();
        Optional<ReplicationRegistryScriptUtxo> found = findUtxo(serverId);
        ReplicationRegistryScriptUtxo currentUtxo = found.orElseThrow(
                () -> new IllegalArgumentException("unknown serverId: " + serverId));
        ReplicationServerState current = currentUtxo.state().orElseThrow();
        if (!current.operator().equals(transactions().paymentKeyHash())) {
            throw new IllegalStateException("configured Cardano identity does not control this registration");
        }
        if (!current.active()) throw new IllegalArgumentException("server is already inactive");
        ReplicationServerState inactive = current.deactivate();
        transactions().spend(currentUtxo, inactive, ReplicationServerMutation.unregister());
        awaitState(inactive);
    }

    private void requireConfiguredIdentity(ReplicationServerState server) {
        String expectedServerId = transactions().serverId();
        if (!expectedServerId.equalsIgnoreCase(server.serverId())) {
            throw new IllegalArgumentException("serverId does not match the configured Cardano verification key");
        }
        String expectedOperator = transactions().paymentKeyHash();
        if (!expectedOperator.equalsIgnoreCase(server.operator())) {
            throw new IllegalArgumentException("operator does not match the configured Cardano payment key");
        }
        if (!server.active()) throw new IllegalArgumentException("new or updated registrations must be active");
    }

    private synchronized Optional<ReplicationRegistryScriptUtxo> findUtxo(String serverId) {
        return liveUtxos().stream()
                .filter(utxo -> utxo.state().map(state -> state.serverId().equalsIgnoreCase(serverId)).orElse(false))
                .findFirst();
    }

    private void awaitState(ReplicationServerState expected) {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (queryServers(true).stream().anyMatch(expected::equals)) return;
            pause();
        }
        throw new IllegalStateException("replication registry transaction did not become observable: "
                + expected.serverId());
    }

    private List<ReplicationServerState> observedStates() {
        if (config.cliBackend() == CardanoCliBackend.CACHE_ONLY) return readSnapshot();
        return liveUtxos().stream().flatMap(utxo -> utxo.state().stream()).toList();
    }

    private synchronized List<ReplicationRegistryScriptUtxo> liveUtxos() {
        var deployment = deployments.read();
        Optional<String> json = runner.queryScriptUtxosJson(deployment.validatorAddress());
        if (json.isEmpty()) return List.of();
        String contents = json.orElseThrow();
        TextFiles.write(config.scriptUtxoCacheFile(), contents);
        List<ReplicationRegistryScriptUtxo> utxos = utxoParser.parse(contents);
        writeSnapshot(utxos.stream().flatMap(utxo -> utxo.state().stream()).toList());
        return utxos;
    }

    private List<ReplicationServerState> readSnapshot() {
        if (!Files.exists(config.stateFile())) return List.of();
        try {
            return JsonSupport.MAPPER.readValue(config.stateFile().toFile(), STATES);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read replication-registry snapshot " + config.stateFile(), ex);
        }
    }

    private void writeSnapshot(List<ReplicationServerState> states) {
        try {
            TextFiles.write(config.stateFile(), JsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(states) + System.lineSeparator());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write replication-registry snapshot " + config.stateFile(), ex);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for replication-registry transaction", ex);
        }
    }

    private synchronized ReplicationRegistryTransactionBuilder transactions() {
        if (transactions == null) {
            transactions = new ReplicationRegistryTransactionBuilder(config, runner, deployments);
        }
        return transactions;
    }
}
