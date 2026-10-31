package org.pubsub.prototype.registry.topic.cardano;

import org.pubsub.prototype.cardano.CardanoConfirmationWaiter;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionLog;
import org.pubsub.prototype.cardano.cli.transaction.SignTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.SubmitTransactionCommand;
import org.pubsub.prototype.constants.CryptoConstants;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.RegistryDataException;
import org.pubsub.prototype.registry.RegistryIdentityException;
import org.pubsub.prototype.registry.topic.CreateTopicRequest;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicRegistry;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.cardano.datum.AikenScriptDataCodec;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoScriptUtxo;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicOperation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicStateTransitions;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoRegistryIdentityValidator;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoTransactionArtifacts;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoTransactionSigner;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.CreateTopicCommand;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.SpendTopicCommand;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads topic state from the configured Cardano script and submits topic transactions.
 * Every read queries live script UTxOs. Trusted deployment metadata identifies the script address
 * and minting policy; no local UTxO snapshot is used for registry decisions.
 */
public final class CardanoTopicRegistry implements TopicRegistry {
    private static final HexFormat HEX = HexFormat.of();
    private static final long SCRIPT_OUTPUT_LOVELACE = 2_000_000L;
    private static final int TOPIC_TOKEN_QUANTITY = 1;

    private final CardanoCommandRunner commandRunner;
    private final CardanoCliUtxoParser utxoParser;
    private final CardanoRegistryDeploymentManager deployments;
    private final AikenScriptDataCodec scriptDataCodec;
    private final CardanoRegistryIdentityValidator identityValidator;
    private final CardanoTransactionArtifacts artifacts;
    private final CardanoTransactionSigner transactionSigner;
    private final CardanoTransactionLog transactionLog;

    /** Creates a registry with the Cardano command backend selected by {@code config}. */
    public CardanoTopicRegistry(CardanoRegistryConfig config) {
        this(config, CardanoCommandRunnerFactory.create(config.cliBackend(), config.runtimeDir()));
    }

    CardanoTopicRegistry(CardanoRegistryConfig config, CardanoCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
        this.utxoParser = new CardanoCliUtxoParser();
        this.deployments = new CardanoRegistryDeploymentManager(config, commandRunner);
        this.scriptDataCodec = new AikenScriptDataCodec();
        this.identityValidator = new CardanoRegistryIdentityValidator();
        this.artifacts = new CardanoTransactionArtifacts(config);
        this.transactionSigner = new CardanoTransactionSigner(config, commandRunner);
        this.transactionLog = new CardanoTransactionLog(config.transactionLogFile());
    }

    @Override
    public List<TopicState> activeTopicsSnapshot() {
        return readOnChainTopics().stream().filter(TopicState::active).toList();
    }

    @Override
    public Optional<TopicState> findTopicState(TopicId topicId) {
        return readOnChainTopics().stream().filter(topic -> topic.topicId().equals(topicId)).findFirst();
    }

    /** Mints the topic token from a consumed payment UTxO and waits for its script output. */
    @Override
    public TopicId createTopic(CreateTopicRequest request) {
        commandRunner.requireCommandExecution();
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();
        String creationSeed = signer.paymentUtxo(commandRunner, Set.of());
        String collateral = signer.collateralUtxo(commandRunner, Set.of(creationSeed));
        String tokenName = creationTokenName(creationSeed);
        TopicId topicId = new TopicId(tokenName);
        String owner = identityValidator.requirePaymentKeyHash(transactionSigner.paymentKeyHash());
        TopicState topic = new TopicState(topicId, request.name(), List.of(owner),
                identityValidator.requirePaymentKeyHashes(request.admins()),
                identityValidator.normalizePublisherKeyIds(request.publishers()),
                request.replicationFactor(), request.retentionPeriod(), true);

        String artifactName = "create-" + topicId;
        Path datum = writeDatum(topic, artifactName);
        Path body = artifacts.transactionBody(artifactName);
        Path signed = artifacts.signedTransaction(artifactName);
        Path mintRedeemer = artifacts.writeMintRedeemer(
                scriptDataCodec.encodeMintRedeemer(creationSeed, tokenName, topicId));
        commandRunner.run(new CreateTopicCommand(commandRunner.networkMagic(), signer.address(),
                creationSeed, collateral, topicOutput(deployment, tokenName), datum,
                topicAsset(deployment, tokenName), deployments.mintingPolicyScript(),
                mintRedeemer, owner, body));

        String logDetails = "topicId=" + topicId + " token=" + deployment.policyId() + "." + tokenName;
        signAndSubmit(body, signed, signer,
                "TOPIC_CREATED " + logDetails, "TOPIC_CREATE_FAILED " + logDetails);
        waitUntilCreated(topicId);
        return topicId;
    }

    @Override
    public void deleteTopic(TopicId topicId) {
        mutate(topicId, TopicMutation.delete());
    }

    @Override
    public void addOwner(TopicId topicId, String owner) {
        mutate(topicId, TopicMutation.addOwner(owner));
    }

    @Override
    public void removeOwner(TopicId topicId, String owner) {
        mutate(topicId, TopicMutation.removeOwner(owner));
    }

    @Override
    public void addAdmin(TopicId topicId, String admin) {
        mutate(topicId, TopicMutation.addAdmin(admin));
    }

    @Override
    public void removeAdmin(TopicId topicId, String admin) {
        mutate(topicId, TopicMutation.removeAdmin(admin));
    }

    @Override
    public void addPublisher(TopicId topicId, String publisher) {
        mutate(topicId, TopicMutation.addPublisher(publisher));
    }

    @Override
    public void removePublisher(TopicId topicId, String publisher) {
        mutate(topicId, TopicMutation.removePublisher(publisher));
    }

    @Override
    public void setReplicationFactor(TopicId topicId, int replicationFactor) {
        mutate(topicId, TopicMutation.setReplicationFactor(replicationFactor));
    }

    @Override
    public void setRetentionPeriod(TopicId topicId, long retentionPeriod) {
        mutate(topicId, TopicMutation.setRetentionPeriod(retentionPeriod));
    }

    /** Stages the built scripts and records their Cardano address and policy ID. */
    public void deploy() {
        deployments.initialize();
    }

    @Override
    public List<TopicState> allTopicsSnapshot() {
        return readOnChainTopics();
    }

    /** Returns the raw live script UTxO query for administration and diagnostics. */
    public String scriptUtxosJson() {
        RegistryDeployment deployment = deployments.read();
        return commandRunner.queryScriptUtxosJson(deployment.validatorAddress()).orElseThrow(
                () -> new RegistryConflictException("Cardano registry is unavailable; script UTxOs cannot be queried")
        );
    }

    /** Builds an unsigned last-owner removal transaction for a contract rejection check. */
    public void buildInvalidLastOwnerRemoval(TopicId topicId, String owner) {
        commandRunner.requireCommandExecution();
        TopicState current = findTopicState(topicId).orElseThrow(() -> new RegistryConflictException("unknown on-chain topicId: " + topicId));
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();
        String ownerHash = identityValidator.requirePaymentKeyHash(owner);
        String artifactName = "direct-last-owner-removal-" + topicId;
        Path datum = artifacts.writeDatum(artifactName, scriptDataCodec.encodeDatum(
                current.topicId(), current.name(), List.of(), current.admins(), current.publishers(),
                current.replicationFactor(), current.retentionPeriod(), current.active(),
                identityValidator::requirePaymentKeyHash));
        Path body = artifacts.transactionBody(artifactName);
        buildTopicSpend(current, new TopicMutation(TopicOperation.REMOVE_OWNER, ownerHash),
                deployment, signer, datum, body);
    }

    /** Builds a replacement topic UTxO, submits it, and waits for the spent output to move. */
    private void mutate(TopicId topicId, TopicMutation mutation) {
        commandRunner.requireCommandExecution();
        TopicState current = findTopicState(topicId).orElseThrow(() -> new RegistryConflictException("unknown on-chain topicId: " + topicId));
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();
        TopicMutation onChainMutation = identityValidator.normalizeMutation(mutation);
        TopicState next = TopicStateTransitions.apply(current, onChainMutation);
        String artifactName = mutation.operationValue() + "-" + topicId;
        Path datum = writeDatum(next, artifactName);
        Path body = artifacts.transactionBody(artifactName);
        Path signed = artifacts.signedTransaction(artifactName);

        String spentUtxo = buildTopicSpend(current, onChainMutation, deployment, signer, datum, body);
        boolean delete = mutation.operation() == TopicOperation.DELETE_TOPIC;
        String logDetails = "operation=" + mutation.operationValue() + " topicId=" + topicId;
        signAndSubmit(body, signed, signer,
                (delete ? "TOPIC_DELETED " : "TOPIC_UPDATED ") + logDetails,
                (delete ? "TOPIC_DELETE_FAILED " : "TOPIC_UPDATE_FAILED ") + logDetails);
        waitUntilMoved(topicId, spentUtxo);
    }

    private String buildTopicSpend(TopicState current, TopicMutation mutation,
                                   RegistryDeployment deployment, CardanoPaymentIdentity signer,
                                   Path datum, Path body) {
        String topicUtxo = topicUtxo(current.topicId())
                .orElseThrow(() -> new RegistryConflictException(
                        "topic UTxO not found on-chain: " + current.topicId()))
                .ref();
        String payment = signer.paymentUtxo(commandRunner, Set.of());
        String collateral = signer.collateralUtxo(commandRunner, Set.of(payment));
        Path redeemer = artifacts.writeTopicRedeemer(mutation.operationValue(),
                scriptDataCodec.encodeTopicRedeemer(mutation, identityValidator::requirePaymentKeyHash));
        commandRunner.run(new SpendTopicCommand(commandRunner.networkMagic(), signer.address(),
                topicUtxo, deployments.validatorScript(), redeemer, payment, collateral,
                topicOutput(deployment, current.topicId().value()), datum,
                transactionSigner.paymentKeyHash(), body));
        return topicUtxo;
    }

    /** Signs and submits a transaction, recording either its success or the stage at which it failed. */
    void signAndSubmit(Path body, Path signed, CardanoPaymentIdentity signer,
                       String successLog, String failureLog) {
        try {
            commandRunner.run(new SignTransactionCommand(body, signer.signingKey(),
                    commandRunner.networkMagic(), signed));
        } catch (RuntimeException ex) {
            appendFailureLog(failureLog, "sign", ex);
            throw ex;
        }
        String submitOutput;
        try {
            submitOutput = commandRunner.run(new SubmitTransactionCommand(signed,
                    commandRunner.networkMagic()));
        } catch (RuntimeException ex) {
            appendFailureLog(failureLog, "submit", ex);
            throw ex;
        }
        transactionLog.append(successLog + " tx=" + normalizedLogValue(submitOutput));
    }

    private void appendFailureLog(String failureLog, String stage, RuntimeException failure) {
        try {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            transactionLog.append(failureLog + " stage=" + stage + " error=" + normalizedLogValue(message));
        } catch (RuntimeException logFailure) {
            failure.addSuppressed(logFailure);
        }
    }

    private static String normalizedLogValue(String value) {
        return value.replace(System.lineSeparator(), " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void waitUntilCreated(TopicId topicId) {
        CardanoConfirmationWaiter.await(
                () -> findTopicState(topicId).isPresent(),
                new RegistryConflictException("submitted Cardano registry transaction did not become observable: " + topicId));
    }

    private void waitUntilMoved(TopicId topicId, String spentUtxo) {
        CardanoConfirmationWaiter.await(
                () -> topicUtxo(topicId).filter(observed -> !observed.ref().equals(spentUtxo)).isPresent(),
                new RegistryConflictException("submitted Cardano registry transaction did not move topic UTxO: " + topicId));
    }

    /** A missing query is an error; an empty on-chain UTxO set is a valid empty registry. */
    private List<TopicState> readOnChainTopics() {
        RegistryDeployment deployment = deployments.read();
        String json = commandRunner.queryScriptUtxosJson(deployment.validatorAddress())
                .orElseThrow(() -> new RegistryConflictException("Cardano topic registry query is unavailable"));
        return parseTopics(json, deployment);
    }

    /** Rejects inconsistent registry tokens before publishing a complete topic snapshot. */
    private List<TopicState> parseTopics(String scriptUtxosJson, RegistryDeployment deployment) {
        var topics = new LinkedHashMap<TopicId, TopicState>();
        for (CardanoScriptUtxo utxo : utxoParser.parse(scriptUtxosJson, deployment.validatorAddress())) {
            if (!utxo.containsPolicy(deployment.policyId())) {
                continue;
            }
            TopicState state = utxo.topicState().orElseThrow(() ->
                    new RegistryDataException("Invalid topic registry datum at " + utxo.ref()));
            String policyAssetPrefix = deployment.policyId() + ".";
            String topicAsset = policyAssetPrefix + state.topicId().value();
            long registryTokenCount = utxo.assets().keySet().stream()
                    .filter(asset -> asset.startsWith(policyAssetPrefix))
                    .count();
            // The token must identify this datum, and each topic UTxO must carry exactly one registry token.
            if (utxo.assets().getOrDefault(topicAsset, 0L) != TOPIC_TOKEN_QUANTITY || registryTokenCount != 1) {
                throw new RegistryIdentityException("Unexpected topic registry token at " + utxo.ref());
            }
            // A topic ID represents one state UTxO across the entire queried registry snapshot.
            if (topics.putIfAbsent(state.topicId(), state) != null) {
                throw new RegistryDataException("Duplicate topic registry state for " + state.topicId());
            }
        }
        return topics.values().stream()
                .sorted(Comparator.comparing(topic -> topic.topicId().value()))
                .toList();
    }

    /** Looks up the current UTxO reference used when spending or confirming a topic. */
    private Optional<CardanoScriptUtxo> topicUtxo(TopicId topicId) {
        RegistryDeployment deployment = deployments.read();
        Optional<String> scriptUtxosJson = commandRunner.queryScriptUtxosJson(deployment.validatorAddress());
        if (scriptUtxosJson.isEmpty()) {
            return Optional.empty();
        }
        String json = scriptUtxosJson.orElseThrow();
        return utxoParser.parse(json, deployment.validatorAddress()).stream()
                .filter(utxo -> utxo.containsPolicy(deployment.policyId()))
                .filter(utxo -> utxo.topicState().map(state -> state.topicId().equals(topicId)).orElse(false))
                .findFirst();
    }

    private Path writeDatum(TopicState topic, String artifactName) {
        return artifacts.writeDatum(artifactName,
                scriptDataCodec.encodeDatum(topic, identityValidator::requirePaymentKeyHash));
    }

    private static String topicOutput(RegistryDeployment deployment, String tokenName) {
        return deployment.validatorAddress() + "+" + SCRIPT_OUTPUT_LOVELACE + "+"
                + topicAsset(deployment, tokenName);
    }

    private static String topicAsset(RegistryDeployment deployment, String tokenName) {
        return TOPIC_TOKEN_QUANTITY + " " + deployment.policyId() + "." + tokenName;
    }

    /** Derives the token name from the creation UTxO using the Aiken policy's byte layout. */
    private static String creationTokenName(String creationSeed) {
        String[] parts = creationSeed.split("#", 2);
        if (parts.length != 2) {
            throw new RegistryConflictException("Invalid UTxO reference: " + creationSeed);
        }
        byte[] txHash = HEX.parseHex(parts[0]);
        long outputIndex = Long.parseLong(parts[1]);
        ByteBuffer bytes = ByteBuffer.allocate(txHash.length + Long.BYTES);
        bytes.put(txHash);
        bytes.putLong(outputIndex);
        try {
            return HEX.formatHex(MessageDigest.getInstance(CryptoConstants.SHA_256_ALGORITHM)
                    .digest(bytes.array()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
