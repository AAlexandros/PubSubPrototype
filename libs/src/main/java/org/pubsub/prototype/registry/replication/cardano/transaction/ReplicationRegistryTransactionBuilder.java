package org.pubsub.prototype.registry.replication.cardano.transaction;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionLog;
import org.pubsub.prototype.cardano.cli.transaction.SignTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.SubmitTransactionCommand;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryConfig;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryDeploymentManager;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryAikenCodec;
import org.pubsub.prototype.registry.replication.cardano.datum.ReplicationRegistryScriptUtxo;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerMutation;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerOperation;
import org.pubsub.prototype.registry.replication.cardano.transaction.command.CreateReplicationServerCommand;
import org.pubsub.prototype.registry.replication.cardano.transaction.command.SpendReplicationServerCommand;
import org.pubsub.prototype.util.TextFiles;

import java.nio.file.Path;
import java.util.Set;

/** Builds, signs, and submits replication-registry registration transactions. */
public final class ReplicationRegistryTransactionBuilder {
    private static final long SCRIPT_OUTPUT_LOVELACE = 2_000_000L;

    private final CardanoReplicationRegistryConfig config;
    private final CardanoCommandRunner runner;
    private final CardanoReplicationRegistryDeploymentManager deployments;
    private final CardanoPaymentIdentity identity;
    private final ReplicationRegistryAikenCodec codec;
    private final CardanoTransactionLog transactionLog;

    public ReplicationRegistryTransactionBuilder(
            CardanoReplicationRegistryConfig config,
            CardanoCommandRunner runner,
            CardanoReplicationRegistryDeploymentManager deployments
    ) {
        this.config = config;
        this.runner = runner;
        this.deployments = deployments;
        this.identity = CardanoPaymentIdentity.load(config.keysDirectory(), config.signer());
        this.codec = new ReplicationRegistryAikenCodec();
        this.transactionLog = new CardanoTransactionLog(config.transactionLogFile());
    }

    public String paymentKeyHash() {
        return identity.paymentKeyHash(runner);
    }

    public String serverId() {
        return identity.serverId();
    }

    public void create(ReplicationServerState server) {
        runner.requireCommandExecution();
        var deployment = deployments.read();
        String name = server.serverId();
        Path datum = writeDatum(server, name);
        Path body = body(name);
        Path signed = signed(name);
        String payment = identity.paymentUtxo(runner, Set.of());
        run(new CreateReplicationServerCommand(runner.networkMagic(), identity.address(), payment,
                deployment.validatorAddress() + "+" + SCRIPT_OUTPUT_LOVELACE, datum,
                paymentKeyHash(), body));
        signAndSubmit(body, signed, "REPLICATION_SERVER_REGISTERED serverId=" + name,
                "REPLICATION_SERVER_REGISTER_FAILED serverId=" + name);
    }

    public void spend(ReplicationRegistryScriptUtxo current, ReplicationServerState next,
                      ReplicationServerMutation mutation) {
        runner.requireCommandExecution();
        var deployment = deployments.read();
        boolean unregister = mutation.operation() == ReplicationServerOperation.UNREGISTER_SERVER;
        String name = (unregister ? "unregister-" : "update-")
                + current.state().orElseThrow().serverId();
        Path datum = writeDatum(next, name);
        Path redeemer = writeRedeemer(mutation, name);
        Path body = body(name);
        Path signed = signed(name);
        String payment = identity.paymentUtxo(runner, Set.of(current.ref()));
        String collateral = identity.collateralUtxo(runner, Set.of(current.ref(), payment));
        String output = deployment.validatorAddress() + "+" + current.lovelace();
        run(new SpendReplicationServerCommand(runner.networkMagic(), identity.address(), current.ref(),
                deployments.validatorScript(), redeemer, payment, collateral, output, datum,
                paymentKeyHash(), body));
        String action = unregister ? "UNREGISTERED" : "UPDATED";
        signAndSubmit(body, signed, "REPLICATION_SERVER_" + action + " serverId=" + next.serverId(),
                "REPLICATION_SERVER_" + action + "_FAILED serverId=" + next.serverId());
    }

    private Path writeDatum(ReplicationServerState state, String name) {
        return TextFiles.write(config.transactionArtifactsDir().resolve(name + ".datum.json"), codec.encodeDatum(state));
    }

    private Path writeRedeemer(ReplicationServerMutation mutation, String name) {
        return TextFiles.write(config.transactionArtifactsDir().resolve(name + ".redeemer.json"), codec.encodeRedeemer(mutation));
    }

    private Path body(String name) {
        return TextFiles.prepare(config.transactionArtifactsDir().resolve(name + ".txbody"));
    }

    private Path signed(String name) {
        return TextFiles.prepare(config.transactionArtifactsDir().resolve(name + ".tx"));
    }

    private String run(CardanoTransactionCommand command) {
        return runner.run(command.argumentArray());
    }

    private void signAndSubmit(Path body, Path signed, String success, String failure) {
        try {
            run(new SignTransactionCommand(body, identity.signingKey(), runner.networkMagic(), signed));
            String tx = run(new SubmitTransactionCommand(signed, runner.networkMagic()));
            transactionLog.append(success + " tx=" + tx.trim());
        } catch (RuntimeException ex) {
            transactionLog.append(failure + " error=" + normalized(ex.getMessage()));
            throw ex;
        }
    }

    private static String normalized(String message) {
        return (message == null ? "transaction failed" : message)
                .replace(System.lineSeparator(), " ").replace('\n', ' ').replace('\r', ' ').trim();
    }
}
