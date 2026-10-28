package org.pubsub.prototype.registry.topic.cardano;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.cardano.cli.CardanoCliUnavailableException;
import org.pubsub.prototype.cardano.CardanoConfirmationWaiter;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionLog;
import org.pubsub.prototype.cardano.cli.transaction.SignTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.SubmitTransactionCommand;
import org.pubsub.prototype.constants.CryptoConstants;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.topic.CreateTopicRequest;
import org.pubsub.prototype.registry.topic.RegistrySnapshot;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicRegistry;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoScriptUtxo;
import org.pubsub.prototype.registry.topic.cardano.datum.AikenScriptDataCodec;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicOperation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicStateTransitions;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoRegistryIdentityValidator;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoTransactionArtifacts;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoTransactionSigner;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.CreateTopicCommand;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.SpendTopicCommand;
import org.pubsub.prototype.util.files.TextFiles;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class CardanoTopicRegistry implements TopicRegistry {
    private static final HexFormat HEX = HexFormat.of();
    private static final long SCRIPT_OUTPUT_LOVELACE = 2_000_000L;
    private static final int TOPIC_TOKEN_QUANTITY = 1;

    private final CardanoRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private final CardanoCliUtxoParser utxoParser;
    private final CardanoRegistryDeploymentManager deployments;
    private final AikenScriptDataCodec scriptDataCodec;
    private final CardanoRegistryIdentityValidator identityValidator;
    private final CardanoTransactionArtifacts artifacts;
    private final CardanoTransactionSigner transactionSigner;
    private final CardanoTransactionLog transactionLog;

    public CardanoTopicRegistry(CardanoRegistryConfig config) {
        this(config, CardanoCommandRunnerFactory.create(config.cliBackend(), config.runtimeDir()));
    }

    CardanoTopicRegistry(CardanoRegistryConfig config, CardanoCommandRunner commandRunner) {
        this.config = config;
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
    public RegistrySnapshot activeTopicsSnapshot() {
        return new RegistrySnapshot(readOnChainTopics().stream().filter(TopicState::active).toList(), Instant.now());
    }

    @Override
    public Optional<TopicState> findTopicState(TopicId topicId) {
        return readOnChainTopics().stream().filter(topic -> topic.topicId().equals(topicId)).findFirst();
    }

    @Override
    public TopicId createTopic(CreateTopicRequest request) {
        requireCommandExecution();
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

    public void deploy() {
        deployments.initialize();
    }

    @Override
    public RegistrySnapshot allTopicsSnapshot() {
        return new RegistrySnapshot(readOnChainTopics(), Instant.now());
    }

    public String scriptUtxosJson() {
        RegistryDeployment deployment = deployments.read();
        String json = commandRunner.queryScriptUtxosJson(deployment.validatorAddress()).orElseThrow(
                () -> new RegistryConflictException("Cardano registry is unavailable; script UTxOs cannot be queried")
        );
        writeScriptUtxoCache(json);
        return json;
    }

    public void buildInvalidLastOwnerRemoval(TopicId topicId, String owner) {
        requireCommandExecution();
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

    private void mutate(TopicId topicId, TopicMutation mutation) {
        requireCommandExecution();
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

    private void requireCommandExecution() {
        try {
            commandRunner.requireCommandExecution();
        } catch (CardanoCliUnavailableException ex) {
            throw new RegistryConflictException(ex.getMessage());
        }
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

    private List<TopicState> readOnChainTopics() {
        RegistryDeployment deployment = deployments.read();
        Optional<String> scriptUtxosJson = commandRunner.queryScriptUtxosJson(deployment.validatorAddress());
        if (scriptUtxosJson.isPresent()) {
            String json = scriptUtxosJson.orElseThrow();
            writeScriptUtxoCache(json);
            return parseTopics(json, deployment);
        }
        if (Files.exists(config.scriptUtxoCacheFile())) {
            return parseTopics(TextFiles.read(config.scriptUtxoCacheFile()), deployment);
        }
        if (Files.exists(config.deploymentFile())) {
            return List.of();
        }
        throw new RegistryConflictException("Cardano registry is unavailable");
    }

    private List<TopicState> parseTopics(String scriptUtxosJson, RegistryDeployment deployment) {
        return utxoParser.parse(scriptUtxosJson).stream()
                .filter(utxo -> utxo.containsPolicy(deployment.policyId()))
                .flatMap(utxo -> utxo.topicState().stream())
                .toList();
    }

    private Optional<CardanoScriptUtxo> topicUtxo(TopicId topicId) {
        RegistryDeployment deployment = deployments.read();
        Optional<String> scriptUtxosJson = commandRunner.queryScriptUtxosJson(deployment.validatorAddress());
        if (scriptUtxosJson.isEmpty()) {
            return Optional.empty();
        }
        String json = scriptUtxosJson.orElseThrow();
        writeScriptUtxoCache(json);
        return utxoParser.parse(json).stream()
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

    private void writeScriptUtxoCache(String json) {
        TextFiles.write(config.scriptUtxoCacheFile(), json);
    }
}
