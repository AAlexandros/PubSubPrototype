package org.pubsub.prototype.registry.topic.cardano.transaction;

import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryDeploymentManager;
import org.pubsub.prototype.registry.topic.cardano.RegistryDeployment;
import org.pubsub.prototype.registry.topic.cardano.datum.AikenScriptDataCodec;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicOperation;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicStateTransitions;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.CreateTopicCommand;
import org.pubsub.prototype.registry.topic.cardano.transaction.command.SpendTopicCommand;
import org.pubsub.prototype.cardano.cli.CardanoCliUnavailableException;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionLog;
import org.pubsub.prototype.cardano.cli.transaction.CardanoTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.SignTransactionCommand;
import org.pubsub.prototype.cardano.cli.transaction.SubmitTransactionCommand;
import org.pubsub.prototype.util.CryptoConstants;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class CardanoTransactionBuilder {
    /** Converts between raw bytes and the printable hexadecimal representation used by Cardano CLI and JSON. */
    private static final HexFormat HEX = HexFormat.of();
    // Two ADA is convienetly enough for paying the on-chain fees
    // In a non-prototype implementatio, minimum ADA for the specific output should be calculated and locked
    private static final long SCRIPT_OUTPUT_LOVELACE = 2_000_000L;
    private static final int TOPIC_TOKEN_QUANTITY = 1;

    private final AikenScriptDataCodec scriptDataCodec = new AikenScriptDataCodec();
    private final CardanoCliUtxoParser utxoParser = new CardanoCliUtxoParser();
    private final CardanoCommandRunner commandRunner;
    private final CardanoRegistryDeploymentManager deployments;
    private final CardanoTransactionSigner transactionSigner;
    private final CardanoRegistryIdentityValidator identityValidator;
    private final CardanoTransactionArtifacts artifacts;
    private final CardanoTransactionLog transactionLog;

    public CardanoTransactionBuilder(
            CardanoRegistryConfig config,
            CardanoCommandRunner commandRunner,
            CardanoRegistryDeploymentManager deployments
    ) {
        this.commandRunner = commandRunner;
        this.deployments = deployments;
        this.identityValidator = new CardanoRegistryIdentityValidator();
        this.transactionSigner = new CardanoTransactionSigner(config, commandRunner, identityValidator);
        this.artifacts = new CardanoTransactionArtifacts(config);
        this.transactionLog = new CardanoTransactionLog(config.transactionLogFile());
    }

    public TopicId createTopic(CreateTopicRequest request) {
        requireCommandExecution();
        
        // Get current validator address and policy id
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();

        // As creation seed, use the UTxO with max lovelace
        String creationSeed = signer.paymentUtxo(commandRunner, Set.of());
        // As collateral mortage, use the UTxO with lowest lovelace
        String collateral = signer.collateralUtxo(commandRunner, Set.of(creationSeed));
        // Create the token name by hashing the raw transaction bytes
        String tokenName = creationTokenName(creationSeed);
        // Create the topic ID by hashing the token name with sha-256
        TopicId topicId = new TopicId(tokenName);
        // Create the initial topic state
        TopicState topic = new TopicState(topicId, request.name(), List.of(transactionSigner.paymentKeyHash()),
                identityValidator.requirePaymentKeyHashes(request.admins()),
                identityValidator.normalizePublisherKeyIds(request.publishers()),
                request.replicationFactor(), request.retentionPeriod(), true);

        // Create a conventional name for artifacts
        String artifactName = "create-" + topicId;
        // Create a new artifact, using the topic as datum, and naming it with the conventional artifact name
        Path datum = writeDatum(topic, artifactName);
        Path transactionBodyFile = artifacts.transactionBody(artifactName);
        Path signedTransactionFile = artifacts.signedTransaction(artifactName);

        // Create the complete topic creation transaction, and save it in transactionBodyFile
        runTransaction(new CreateTopicCommand(
                commandRunner.networkMagic(),
                signer.address(),
                creationSeed,
                collateral,
                topicOutput(deployment, tokenName),
                datum,
                topicAsset(deployment, tokenName),
                deployments.mintingPolicyScript(),
                writeMintRedeemer(creationSeed, tokenName, topicId),
                transactionSigner.paymentKeyHash(),
                transactionBodyFile
        ));

        // Sign and submit the topic creation transaction
        String logDetails = "topicId=" + topicId + " token=" + deployment.policyId() + "." + tokenName;
        signAndSubmit(
                transactionBodyFile,
                signedTransactionFile,
                signer,
                "TOPIC_CREATED " + logDetails,
                "TOPIC_CREATE_FAILED " + logDetails
        );
        return topicId;
    }

    public String updateTopic(TopicState current, TopicMutation mutation) {
        requireCommandExecution();
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();
        TopicMutation onChainMutation = identityValidator.normalizeMutation(mutation);
        TopicState next = TopicStateTransitions.apply(current, onChainMutation);
        String artifactName = mutation.operationValue() + "-" + current.topicId();
        Path datum = writeDatum(next, artifactName);
        Path transactionBodyFile = artifacts.transactionBody(artifactName);
        Path signedTransactionFile = artifacts.signedTransaction(artifactName);

        String topicUtxo = buildTopicSpend(deployment, signer, current, onChainMutation, datum, transactionBodyFile);
        boolean delete = mutation.operation() == TopicOperation.DELETE_TOPIC;
        String logDetails = "operation=" + mutation.operationValue() + " topicId=" + current.topicId();
        signAndSubmit(
                transactionBodyFile,
                signedTransactionFile,
                signer,
                (delete ? "TOPIC_DELETED " : "TOPIC_UPDATED ") + logDetails,
                (delete ? "TOPIC_DELETE_FAILED " : "TOPIC_UPDATE_FAILED ") + logDetails
        );
        return topicUtxo;
    }

    public void buildInvalidLastOwnerRemoval(TopicState current, String owner) {
        requireCommandExecution();
        RegistryDeployment deployment = deployments.read();
        CardanoPaymentIdentity signer = transactionSigner.identity();
        String ownerHash = identityValidator.requirePaymentKeyHash(owner);
        String artifactName = "direct-last-owner-removal-" + current.topicId();
        Path datum = writeDatumWithOwners(current, List.of(), artifactName);
        Path transactionBodyFile = artifacts.transactionBody(artifactName);
        buildTopicSpend(
                deployment,
                signer,
                current,
                new TopicMutation(TopicOperation.REMOVE_OWNER, ownerHash),
                datum,
                transactionBodyFile
        );
    }

    private String buildTopicSpend(
            RegistryDeployment deployment,
            CardanoPaymentIdentity signer,
            TopicState current,
            TopicMutation mutation,
            Path datum,
            Path transactionBodyFile
    ) {
        String topicUtxo = findTopicUtxo(deployment, current);
        String payment = signer.paymentUtxo(commandRunner, Set.of());
        String collateral = signer.collateralUtxo(commandRunner, Set.of(payment));
        runTransaction(new SpendTopicCommand(
                commandRunner.networkMagic(),
                signer.address(),
                topicUtxo,
                deployments.validatorScript(),
                writeTopicRedeemer(mutation),
                payment,
                collateral,
                topicOutput(deployment, current.topicId().value()),
                datum,
                transactionSigner.paymentKeyHash(),
                transactionBodyFile
        ));
        return topicUtxo;
    }

    /** Signs and submits a transaction, recording either its success or the stage at which it failed. */
    String signAndSubmit(
            Path transactionBodyFile,
            Path signedTransactionFile,
            CardanoPaymentIdentity signer,
            String successLog,
            String failureLog
    ) {
        try {
            runTransaction(new SignTransactionCommand(
                    transactionBodyFile,
                    signer.signingKey(),
                    commandRunner.networkMagic(),
                    signedTransactionFile
            ));
        } catch (RuntimeException ex) {
            appendFailureLog(failureLog, "sign", ex);
            throw ex;
        }
        String submitOutput;
        try {
            submitOutput = runTransaction(new SubmitTransactionCommand(signedTransactionFile, commandRunner.networkMagic()));
        } catch (RuntimeException ex) {
            appendFailureLog(failureLog, "submit", ex);
            throw ex;
        }
        transactionLog.append(successLog + " tx=" + normalizedLogValue(submitOutput));
        return submitOutput;
    }

    // Private helpers

    private String runTransaction(CardanoTransactionCommand command) {
        return commandRunner.run(command.argumentArray());
    }

    private static String topicOutput(RegistryDeployment deployment, String tokenName) {
        return deployment.validatorAddress() + "+" + SCRIPT_OUTPUT_LOVELACE + "+"
                + topicAsset(deployment, tokenName);
    }

    private static String topicAsset(RegistryDeployment deployment, String tokenName) {
        return TOPIC_TOKEN_QUANTITY + " " + deployment.policyId() + "." + tokenName;
    }

    private String findTopicUtxo(RegistryDeployment deployment, TopicState topic) {
        String json = commandRunner.queryScriptUtxosJson(deployment.validatorAddress()).orElseThrow(
                () -> new RegistryConflictException("Cardano registry is unavailable; cannot find topic UTxO"));
        return utxoParser.parse(json).stream()
                .filter(utxo -> utxo.containsPolicy(deployment.policyId()))
                .filter(utxo -> utxo.topicState().map(state -> state.topicId().equals(topic.topicId())).orElse(false))
                .findFirst()
                .orElseThrow(() -> new RegistryConflictException("topic UTxO not found on-chain: " + topic.topicId()))
                .ref();
    }

    private Path writeDatum(TopicState topic, String name) {
        return artifacts.writeDatum(
                name,
                scriptDataCodec.encodeDatum(topic, identityValidator::requirePaymentKeyHash)
        );
    }

    private Path writeDatumWithOwners(TopicState topic, List<String> owners, String name) {
        return artifacts.writeDatum(
                name,
                scriptDataCodec.encodeDatum(
                        topic.topicId(),
                        topic.name(),
                        owners,
                        topic.admins(),
                        topic.publishers(),
                        topic.replicationFactor(),
                        topic.retentionPeriod(),
                        topic.active(),
                        identityValidator::requirePaymentKeyHash
                )
        );
    }

    private Path writeMintRedeemer(String creationSeed, String tokenName, TopicId topicId) {
        return artifacts.writeMintRedeemer(
                scriptDataCodec.encodeMintRedeemer(creationSeed, tokenName, topicId)
        );
    }

    private Path writeTopicRedeemer(TopicMutation mutation) {
        return artifacts.writeTopicRedeemer(
                mutation.operationValue(),
                scriptDataCodec.encodeTopicRedeemer(mutation, identityValidator::requirePaymentKeyHash)
        );
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

    /**
     * Derives the topic token name from its creation UTxO exactly as the Aiken minting policy does.
     *
     * <p>Cardano CLI represents an output reference as {@code <transaction-id-hex>#<output-index>}.
     * The transaction ID is 32 raw ledger bytes displayed as 64 printable hexadecimal characters.
     * This method decodes those characters back to the original 32 bytes, appends the output index as an eight-byte big-endian integer, and hashes the resulting
     * 40 bytes with SHA-256. Hashing the hexadecimal characters directly would produce a different value
     * from the on-chain policy.
     *
     * <p>The 32-byte digest is encoded as 64 hexadecimal characters again because {@link TopicId}, JSON,
     * and Cardano CLI expose byte arrays as printable text. The encoding changes only the representation,
     * not the digest value represented by it.
     */
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
        return sha256Hex(bytes.array());
    }

    /** Returns the SHA-256 digest as lowercase hexadecimal text: 32 digest bytes become 64 characters. */
    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance(CryptoConstants.SHA_256_ALGORITHM).digest(value);
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
