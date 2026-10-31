package org.pubsub.prototype.registry.replication.cardano;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.cardano.CardanoConfirmationWaiter;
import org.pubsub.prototype.registry.replication.ReplicationRegistryClient;
import org.pubsub.prototype.registry.RegistryDataException;
import org.pubsub.prototype.registry.replication.ReplicationRegistryReader;
import org.pubsub.prototype.registry.replication.ReplicationServerState;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryScriptUtxo;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryUtxoParser;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerMutation;
import org.pubsub.prototype.registry.replication.cardano.transaction.ReplicationRegistryTransactionBuilder;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Cardano-backed reader and transaction client for replication-server registrations. */
public final class CardanoReplicationRegistry implements ReplicationRegistryClient, ReplicationRegistryReader {

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
        CardanoConfirmationWaiter.await(
                () -> queryServers(true).stream().anyMatch(expected::equals),
                new IllegalStateException("replication registry transaction did not become observable: "
                        + expected.serverId()));
    }

    private List<ReplicationServerState> observedStates() {
        var states = new LinkedHashMap<String, ReplicationServerState>();
        for (ReplicationRegistryScriptUtxo utxo : liveUtxos()) {
            ReplicationServerState state = utxo.state().orElseThrow(() ->
                    new RegistryDataException("Invalid replication registry datum at " + utxo.ref()));
            if (states.putIfAbsent(state.serverId(), state) != null) {
                throw new RegistryDataException("Duplicate replication server registration: " + state.serverId());
            }
        }
        return List.copyOf(states.values());
    }

    private synchronized List<ReplicationRegistryScriptUtxo> liveUtxos() {
        var deployment = deployments.read();
        Optional<String> json = runner.queryScriptUtxosJson(deployment.validatorAddress());
        if (json.isEmpty()) throw new IllegalStateException("Cardano replication registry query is unavailable");
        String contents = json.orElseThrow();
        return utxoParser.parse(contents, deployment.validatorAddress());
    }

    private synchronized ReplicationRegistryTransactionBuilder transactions() {
        if (transactions == null) {
            transactions = new ReplicationRegistryTransactionBuilder(config, runner, deployments);
        }
        return transactions;
    }
}
