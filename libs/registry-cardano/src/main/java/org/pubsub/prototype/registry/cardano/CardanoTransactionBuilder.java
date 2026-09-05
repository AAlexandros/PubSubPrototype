package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CardanoTransactionBuilder {
    private static final HexFormat HEX = HexFormat.of();
    private final CardanoRegistryConfig config;
    private final AikenScriptDataCodec scriptDataCodec = new AikenScriptDataCodec();
    private final Map<String, String> keyHashCache = new HashMap<>();
    private final CardanoCli cli;

    CardanoTransactionBuilder(CardanoRegistryConfig config, CardanoCli cli) {
        this.config = config;
        this.cli = cli;
    }

    CardanoRegistryConfig config() {
        return config;
    }

    RegistryDeployment deploy() {
        requireCardano("deploy");
        Path validator = validatorScriptPath();
        Path policy = mintingPolicyPath();
        if (!Files.exists(validator) || !Files.exists(policy)) {
            throw new RegistryConflictException("Aiken build artifacts are missing; run scripts/registry/build.sh first");
        }
        String validatorAddress = cli.run(
                "cardano-cli", "address", "build",
                "--payment-script-file", validator.toString(),
                "--testnet-magic", networkMagic()
        ).trim();
        String policyId = cli.run("cardano-cli", "latest", "transaction", "policyid", "--script-file", policy.toString()).trim();
        return new RegistryDeployment(validatorAddress, policyId);
    }

    RegistryDeployment readDeployment() {
        if (!Files.exists(config.deploymentFile())) {
            return deploy();
        }
        try {
            Map<String, String> values = EnvFile.read(config.deploymentFile());
            String address = first(values, CardanoRegistryNames.RuntimeKey.TOPIC_REGISTRY_VALIDATOR_ADDRESS,
                    CardanoRegistryNames.RuntimeKey.LEGACY_REGISTRY_VALIDATOR_ADDRESS);
            String policy = first(values, CardanoRegistryNames.RuntimeKey.TOPIC_POLICY_ID,
                    CardanoRegistryNames.RuntimeKey.LEGACY_TOPIC_POLICY_ID);
            if (address == null || policy == null) {
                throw new RegistryConflictException("registry deployment file is missing validator address or policy id");
            }
            return new RegistryDeployment(address, policy);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read registry deployment", ex);
        }
    }

    void writeDeployment(RegistryDeployment deployment) {
        try {
            Files.createDirectories(config.runtimeDir());
            String body = envLine(CardanoRegistryNames.RuntimeKey.TOPIC_REGISTRY_VALIDATOR_ADDRESS, deployment.validatorAddress())
                    + envLine(CardanoRegistryNames.RuntimeKey.TOPIC_POLICY_ID, deployment.policyId())
                    + envLine(CardanoRegistryNames.RuntimeKey.REGISTRY_BACKEND, "cardano")
                    + envLine(CardanoRegistryNames.RuntimeKey.REGISTRY_SCRIPT_UTXO_CACHE, config.scriptUtxoCacheFile().toAbsolutePath())
                    + envLine(CardanoRegistryNames.RuntimeKey.REGISTRY_TX_ARTIFACTS_DIR, config.transactionArtifactsDir().toAbsolutePath());
            Files.writeString(config.deploymentFile(), body, StandardCharsets.UTF_8);
            if (!Files.exists(config.scriptUtxoCacheFile())) {
                Files.writeString(config.scriptUtxoCacheFile(), "{}", StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write registry deployment", ex);
        }
    }

    TopicId createTopic(CreateTopicRequest request) {
        requireCardano("createTopic");
        RegistryDeployment deployment = readDeployment();
        CardanoIdentity signer = CardanoIdentity.load(config, config.signer());
        String creationSeed = signer.firstUtxo(cli);
        String collateral = signer.collateralUtxo(cli, Set.of(creationSeed));
        String tokenName = creationTokenName(creationSeed);
        TopicId topicId = new TopicId(tokenName);
        TopicState topic = new TopicState(topicId, request.name(), resolveActorHashes(List.of(config.signer())),
                resolveActorHashes(request.admins()), rawPublisherKeyIds(request.publishers()),
                request.replicationFactor(), request.retentionPeriod(), true);
        Path datum = writeDatum(topic, "create-" + topicId);
        Path body = artifactUnchecked("create-" + topicId + ".txbody");
        Path signed = artifactUnchecked("create-" + topicId + ".tx");

        cli.run(
                "cardano-cli", "latest", "transaction", "build",
                "--testnet-magic", networkMagic(),
                "--change-address", signer.address(),
                "--tx-in", creationSeed,
                "--tx-in-collateral", collateral,
                "--tx-out", deployment.validatorAddress() + "+2000000+" + "1 " + deployment.policyId() + "." + tokenName,
                "--tx-out-inline-datum-file", datum.toString(),
                "--mint", "1 " + deployment.policyId() + "." + tokenName,
                "--mint-script-file", mintingPolicyPath().toString(),
                "--mint-redeemer-file", writeMintRedeemer(creationSeed, tokenName, topicId).toString(),
                "--required-signer-hash", signer.vkeyHash(cli),
                "--out-file", body.toString()
        );
        cli.run("cardano-cli", "latest", "transaction", "sign",
                "--tx-body-file", body.toString(),
                "--signing-key-file", signer.signingKey().toString(),
                "--testnet-magic", networkMagic(),
                "--out-file", signed.toString());
        String submitOutput = cli.run("cardano-cli", "latest", "transaction", "submit", "--tx-file", signed.toString(), "--testnet-magic", networkMagic());
        appendTx("TOPIC_CREATED topicId=" + topicId + " token=" + deployment.policyId() + "." + tokenName
                + " tx=" + normalizedSubmitOutput(submitOutput));
        return topicId;
    }

    String updateTopic(TopicState current, TopicMutation mutation) {
        requireCardano(mutation.operationValue());
        RegistryDeployment deployment = readDeployment();
        CardanoIdentity signer = CardanoIdentity.load(config, config.signer());
        TopicMutation onChainMutation = resolveMutation(mutation);
        TopicState next = TopicStateTransitions.apply(current, onChainMutation);
        String topicToken = current.topicId().value();
        Path datum = writeDatum(next, mutation.operationValue() + "-" + current.topicId());
        Path body = artifactUnchecked(mutation.operationValue() + "-" + current.topicId() + ".txbody");
        Path signed = artifactUnchecked(mutation.operationValue() + "-" + current.topicId() + ".tx");

        String topicUtxo = findTopicUtxo(deployment, current);
        String payment = signer.paymentUtxo(cli, Set.of());
        String collateral = signer.collateralUtxo(cli, Set.of(payment));
        cli.run(
                "cardano-cli", "latest", "transaction", "build",
                "--testnet-magic", networkMagic(),
                "--change-address", signer.address(),
                "--tx-in", topicUtxo,
                "--tx-in-script-file", validatorScriptPath().toString(),
                "--tx-in-inline-datum-present",
                "--tx-in-redeemer-file", writeTopicRedeemer(onChainMutation).toString(),
                "--tx-in", payment,
                "--tx-in-collateral", collateral,
                "--tx-out", deployment.validatorAddress() + "+2000000+" + "1 " + deployment.policyId() + "." + topicToken,
                "--tx-out-inline-datum-file", datum.toString(),
                "--required-signer-hash", signer.vkeyHash(cli),
                "--out-file", body.toString()
        );
        cli.run("cardano-cli", "latest", "transaction", "sign",
                "--tx-body-file", body.toString(),
                "--signing-key-file", signer.signingKey().toString(),
                "--testnet-magic", networkMagic(),
                "--out-file", signed.toString());
        String submitOutput = cli.run("cardano-cli", "latest", "transaction", "submit", "--tx-file", signed.toString(), "--testnet-magic", networkMagic());
        appendTx((mutation.operation() == TopicOperation.DELETE_TOPIC ? "TOPIC_DELETED" : "TOPIC_UPDATED")
                + " operation=" + mutation.operationValue()
                + " topicId=" + current.topicId()
                + " tx=" + normalizedSubmitOutput(submitOutput));
        return topicUtxo;
    }

    void buildInvalidLastOwnerRemoval(TopicState current, String owner) {
        requireCardano("directLastOwnerRemoval");
        RegistryDeployment deployment = readDeployment();
        CardanoIdentity signer = CardanoIdentity.load(config, config.signer());
        String ownerHash = resolveActorHash(owner);
        String topicToken = current.topicId().value();
        Path datum = writeDatumWithOwners(current, List.of(), "direct-last-owner-removal-" + current.topicId());
        Path body = artifactUnchecked("direct-last-owner-removal-" + current.topicId() + ".txbody");

        String topicUtxo = findTopicUtxo(deployment, current);
        String payment = signer.paymentUtxo(cli, Set.of());
        String collateral = signer.collateralUtxo(cli, Set.of(payment));
        cli.run(
                "cardano-cli", "latest", "transaction", "build",
                "--testnet-magic", networkMagic(),
                "--change-address", signer.address(),
                "--tx-in", topicUtxo,
                "--tx-in-script-file", validatorScriptPath().toString(),
                "--tx-in-inline-datum-present",
                "--tx-in-redeemer-file", writeTopicRedeemer(new TopicMutation(TopicOperation.REMOVE_OWNER, ownerHash)).toString(),
                "--tx-in", payment,
                "--tx-in-collateral", collateral,
                "--tx-out", deployment.validatorAddress() + "+2000000+" + "1 " + deployment.policyId() + "." + topicToken,
                "--tx-out-inline-datum-file", datum.toString(),
                "--required-signer-hash", signer.vkeyHash(cli),
                "--out-file", body.toString()
        );
    }

    private String findTopicUtxo(RegistryDeployment deployment, TopicState topic) {
        String json = cli.queryScriptUtxosJson(deployment.validatorAddress()).orElseThrow(
                () -> new RegistryConflictException("Cardano registry is unavailable; cannot find topic UTxO"));
        return new CardanoCliUtxoParser().parse(json).stream()
                .filter(utxo -> utxo.containsPolicy(deployment.policyId()))
                .filter(utxo -> utxo.topicState().map(state -> state.topicId().equals(topic.topicId())).orElse(false))
                .findFirst()
                .orElseThrow(() -> new RegistryConflictException("topic UTxO not found on-chain: " + topic.topicId()))
                .ref();
    }

    private Path writeDatum(TopicState topic, String name) {
        try {
            Path file = artifactUnchecked(name + ".datum.json");
            Files.writeString(file, scriptDataCodec.encodeDatum(topic, this::resolveActorHash), StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write datum", ex);
        }
    }

    private Path writeDatumWithOwners(TopicState topic, List<String> owners, String name) {
        try {
            Path file = artifactUnchecked(name + ".datum.json");
            Files.writeString(file, scriptDataCodec.encodeDatum(
                    topic.topicId(),
                    topic.name(),
                    owners,
                    topic.admins(),
                    topic.publishers(),
                    topic.replicationFactor(),
                    topic.retentionPeriod(),
                    topic.active(),
                    this::resolveActorHash), StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write malformed datum", ex);
        }
    }

    private Path writeMintRedeemer(String creationSeed, String tokenName, TopicId topicId) {
        try {
            Path file = artifactUnchecked("createTopic.redeemer.json");
            Files.writeString(file, scriptDataCodec.encodeMintRedeemer(creationSeed, tokenName, topicId), StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write mint redeemer", ex);
        }
    }

    private Path writeTopicRedeemer(TopicMutation mutation) {
        try {
            Path file = artifactUnchecked(mutation.operationValue() + ".redeemer.json");
            Files.writeString(file, scriptDataCodec.encodeTopicRedeemer(mutation, this::resolveActorHash), StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write topic redeemer", ex);
        }
    }

    private TopicMutation resolveMutation(TopicMutation mutation) {
        if (!mutation.operation().hasActorValue()) {
            return mutation;
        }
        if (mutation.operation() == TopicOperation.ADD_PUBLISHER || mutation.operation() == TopicOperation.REMOVE_PUBLISHER) {
            return new TopicMutation(mutation.operation(), rawPublisherKeyId(mutation.value()));
        }
        return new TopicMutation(mutation.operation(), resolveActorHash(mutation.value()));
    }

    private List<String> resolveActorHashes(List<String> actors) {
        List<String> hashes = new ArrayList<>();
        for (String actor : actors) {
            hashes.add(resolveActorHash(actor));
        }
        return List.copyOf(hashes);
    }

    private String resolveActorHash(String actor) {
        if (actor.matches("[0-9a-fA-F]{56}")) {
            return actor.toLowerCase();
        }
        return keyHashCache.computeIfAbsent(actor, name -> CardanoIdentity.load(config, name).vkeyHash(cli));
    }

    private static List<String> rawPublisherKeyIds(List<String> publishers) {
        List<String> values = new ArrayList<>();
        for (String publisher : publishers) {
            values.add(rawPublisherKeyId(publisher));
        }
        return List.copyOf(values);
    }

    private static String rawPublisherKeyId(String publisher) {
        if (!publisher.matches("[0-9a-fA-F]{64}")) {
            throw new RegistryConflictException("publisher must be a 256-bit event key ID: " + publisher);
        }
        return publisher.toLowerCase();
    }

    private Path artifact(String filename) throws IOException {
        Files.createDirectories(config.transactionArtifactsDir());
        return config.transactionArtifactsDir().resolve(filename);
    }

    private void appendTx(String line) {
        try {
            Files.createDirectories(config.runtimeDir());
            Files.writeString(config.transactionLogFile(), Instant.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to append registry transaction log", ex);
        }
    }

    private static String normalizedSubmitOutput(String submitOutput) {
        return submitOutput.replace(System.lineSeparator(), " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private Path artifactUnchecked(String filename) {
        try {
            return artifact(filename);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to prepare registry transaction artifact", ex);
        }
    }

    private String networkMagic() {
        String magic = cli.readNetworkEnv().get(CardanoRegistryNames.RuntimeKey.NETWORK_MAGIC.value());
        if (magic == null || magic.isBlank()) {
            throw new RegistryConflictException(CardanoRegistryNames.RuntimeKey.NETWORK_MAGIC.value() + " is missing from network.env");
        }
        return magic;
    }

    private void requireCardano(String operation) {
        if (!cli.canSubmitTransactions()) {
            throw new RegistryConflictException("Cardano registry is unavailable for " + operation);
        }
    }

    private Path validatorScriptPath() {
        return mountedScript("topic-state.plutus.json");
    }

    private Path mintingPolicyPath() {
        return mountedScript("topic-policy.plutus.json");
    }

    private Path mountedScript(String filename) {
        Path source = config.projectRoot().resolve(Path.of("contracts", "topic-registry", "build", "cardano-cli", filename));
        Path target = config.runtimeDir().resolve("scripts").resolve(filename).toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to stage registry script for cardano-cli: " + source, ex);
        }
    }

    private static String first(Map<String, String> values, CardanoRegistryNames.RuntimeKey... names) {
        for (CardanoRegistryNames.RuntimeKey name : names) {
            String value = values.get(name.value());
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String envLine(CardanoRegistryNames.RuntimeKey name, Object value) {
        return name.value() + "=" + value + System.lineSeparator();
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

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

    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
