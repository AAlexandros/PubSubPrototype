package org.pubsub.prototype.registry.topic.cardano;

import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.RegistrySnapshot;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicRegistry;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.topic.cardano.datum.CardanoScriptUtxo;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;
import org.pubsub.prototype.registry.topic.cardano.transaction.CardanoTransactionBuilder;
import org.pubsub.prototype.util.TextFiles;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class CardanoTopicRegistry implements TopicRegistry {
    private final CardanoRegistryConfig config;
    private final CardanoCommandRunner commandRunner;
    private final CardanoCliUtxoParser utxoParser;
    private final CardanoRegistryDeploymentManager deployments;
    private final CardanoTransactionBuilder transactions;

    public CardanoTopicRegistry(CardanoRegistryConfig config) {
        this.config = config;
        this.commandRunner = CardanoCommandRunnerFactory.create(config.cliBackend(), config.runtimeDir());
        this.utxoParser = new CardanoCliUtxoParser();
        this.deployments = new CardanoRegistryDeploymentManager(config, commandRunner);
        this.transactions = new CardanoTransactionBuilder(config, commandRunner, deployments);
    }

    @Override
    public RegistrySnapshot snapshot() {
        return new RegistrySnapshot(readOnChainTopics().stream().filter(TopicState::active).toList(), Instant.now());
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

    public void deploy() {
        deployments.initialize();
    }

    /** Returns active and tombstoned topics visible to the registry. */
    public RegistrySnapshot snapshotIncludingTombstones() {
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

    private void writeScriptUtxoCache(String json) {
        TextFiles.write(config.scriptUtxoCacheFile(), json);
    }
}
