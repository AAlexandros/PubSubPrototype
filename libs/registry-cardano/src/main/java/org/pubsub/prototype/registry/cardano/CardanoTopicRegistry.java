package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.RegistrySnapshot;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicRegistry;
import org.pubsub.prototype.registry.TopicState;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class CardanoTopicRegistry implements TopicRegistry {
    private final TopicDatumCodec codec;
    private final CardanoCli cardanoCli;
    private final CardanoCliUtxoParser utxoParser;
    private final CardanoTransactionBuilder transactions;

    public CardanoTopicRegistry(CardanoRegistryConfig config) {
        this(config, new TopicDatumCodec());
    }

    CardanoTopicRegistry(CardanoRegistryConfig config, TopicDatumCodec codec) {
        this.codec = codec;
        this.cardanoCli = new CardanoCli(config);
        this.utxoParser = new CardanoCliUtxoParser();
        this.transactions = new CardanoTransactionBuilder(config, cardanoCli);
    }

    public static CardanoTopicRegistry fromNetworkEnv(Path networkEnv, String signer) {
        Path runtimeDir = networkEnv.toAbsolutePath().getParent().resolve("registry");
        return new CardanoTopicRegistry(new CardanoRegistryConfig(runtimeDir, signer));
    }

    public void deploy() {
        transactions.writeDeployment(transactions.deploy());
    }

    @Override
    public RegistrySnapshot snapshot() {
        return new RegistrySnapshot(readOnChainTopics().stream().filter(TopicState::active).toList(), Instant.now());
    }

    public RegistrySnapshot snapshotIncludingTombstones() {
        return new RegistrySnapshot(readOnChainTopics(), Instant.now());
    }

    @Override
    public Optional<TopicState> topic(TopicId topicId) {
        return readOnChainTopics().stream().filter(topic -> topic.topicId().equals(topicId)).findFirst();
    }

    @Override
    public TopicId createTopic(CreateTopicRequest request) {
        TopicId topicId = transactions.createTopic(request);
        waitUntilChanged(topicId, Optional.empty());
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

    public String encodeDatum(TopicState topic) {
        return codec.encode(topic);
    }

    public String scriptUtxosJson() {
        RegistryDeployment deployment = transactions.readDeployment();
        String json = cardanoCli.queryScriptUtxosJson(deployment.validatorAddress()).orElseThrow(
                () -> new RegistryConflictException("Cardano registry is unavailable; script UTxOs cannot be queried")
        );
        writeScriptUtxoCache(json);
        return json;
    }

    public void buildInvalidLastOwnerRemoval(TopicId topicId, String owner) {
        TopicState current = topic(topicId).orElseThrow(() -> new RegistryConflictException("unknown on-chain topicId: " + topicId));
        transactions.buildInvalidLastOwnerRemoval(current, owner);
    }

    private void mutate(TopicId topicId, TopicMutation mutation) {
        TopicState current = topic(topicId).orElseThrow(() -> new RegistryConflictException("unknown on-chain topicId: " + topicId));
        String spentTopicUtxo = transactions.updateTopic(current, mutation);
        waitUntilMoved(topicId, spentTopicUtxo);
    }

    private void waitUntilChanged(TopicId topicId, Optional<TopicState> previous) {
        for (int attempt = 0; attempt < 60; attempt++) {
            Optional<TopicState> observed = topic(topicId);
            if (observed.isPresent() && !observed.equals(previous)) {
                return;
            }
            sleep();
        }
        throw new RegistryConflictException("submitted Cardano registry transaction did not become observable: " + topicId);
    }

    private void waitUntilMoved(TopicId topicId, String spentUtxo) {
        for (int attempt = 0; attempt < 60; attempt++) {
            Optional<CardanoScriptUtxo> observed = topicUtxo(topicId);
            if (observed.isPresent() && !observed.orElseThrow().ref().equals(spentUtxo)) {
                return;
            }
            sleep();
        }
        throw new RegistryConflictException("submitted Cardano registry transaction did not move topic UTxO: " + topicId);
    }

    private static void sleep() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Cardano registry transaction", ex);
        }
    }

    private List<TopicState> readOnChainTopics() {
        RegistryDeployment deployment = transactions.readDeployment();
        Optional<String> scriptUtxosJson = cardanoCli.queryScriptUtxosJson(deployment.validatorAddress());
        if (scriptUtxosJson.isPresent()) {
            String json = scriptUtxosJson.orElseThrow();
            writeScriptUtxoCache(json);
            return parseTopics(json, deployment);
        }
        if (Files.exists(transactions.config().scriptUtxoCacheFile())) {
            try {
                return parseTopics(Files.readString(transactions.config().scriptUtxoCacheFile(), StandardCharsets.UTF_8), deployment);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read Cardano script UTxO cache", ex);
            }
        }
        if (Files.exists(transactions.config().deploymentFile())) {
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
        RegistryDeployment deployment = transactions.readDeployment();
        Optional<String> scriptUtxosJson = cardanoCli.queryScriptUtxosJson(deployment.validatorAddress());
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

    private void writeScriptUtxoCache(String json) {
        try {
            Files.createDirectories(transactions.config().runtimeDir());
            Files.writeString(transactions.config().scriptUtxoCacheFile(), json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write Cardano script UTxO cache", ex);
        }
    }
}
