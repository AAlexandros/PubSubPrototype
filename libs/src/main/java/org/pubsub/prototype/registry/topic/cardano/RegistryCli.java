package org.pubsub.prototype.registry.topic.cardano;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.util.JsonSupport;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class RegistryCli {
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;

    private RegistryCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: RegistryCli <runtime-dir> <cache-only|host|devnet> <command> ...");
        }
        Path runtimeDir = Path.of(args[0]);
        CardanoCliBackend backend = CardanoCliBackend.parse(args[1]);
        String command = args[2];
        String signer = args.length > 3 ? args[3] : "registry-deployer";
        CardanoTopicRegistry registry = new CardanoTopicRegistry(new CardanoRegistryConfig(runtimeDir, signer, backend));
        switch (command) {
            case "deploy" -> {
                registry.deploy();
                System.out.println("REGISTRY_DEPLOYED");
            }
            case "query" -> {
                boolean all = contains(args, "--all");
                System.out.println(pretty(
                        all ? registry.snapshotIncludingTombstones() : registry.snapshot(),
                        "registry snapshot"
                ));
            }
            case "utxos" -> System.out.println(registry.scriptUtxosJson());
            case "topic" -> {
                TopicId topicId = new TopicId(args[4]);
                TopicState topic = registry.topic(topicId).orElseThrow(() -> new IllegalArgumentException("unknown topicId"));
                System.out.println(pretty(topic, "topic"));
            }
            case "create" -> {
                TopicId topicId = registry.createTopic(new CreateTopicRequest(
                        args[4],
                        csv(args[5]),
                        csv(args[6]),
                        Integer.parseInt(args[7]),
                        Long.parseLong(args[8])
                ));
                System.out.println(topicId);
            }
            case "delete" -> registry.deleteTopic(new TopicId(args[4]));
            case "add-owner" -> registry.addOwner(new TopicId(args[4]), args[5]);
            case "remove-owner" -> registry.removeOwner(new TopicId(args[4]), args[5]);
            case "add-admin" -> registry.addAdmin(new TopicId(args[4]), args[5]);
            case "remove-admin" -> registry.removeAdmin(new TopicId(args[4]), args[5]);
            case "add-publisher" -> registry.addPublisher(new TopicId(args[4]), args[5]);
            case "remove-publisher" -> registry.removePublisher(new TopicId(args[4]), args[5]);
            case "set-replication-factor" -> registry.setReplicationFactor(new TopicId(args[4]), Integer.parseInt(args[5]));
            case "set-retention-period" -> registry.setRetentionPeriod(new TopicId(args[4]), Long.parseLong(args[5]));
            case "direct-last-owner-removal" -> registry.buildInvalidLastOwnerRemoval(new TopicId(args[4]), args[5]);
            default -> throw new IllegalArgumentException("Unsupported command: " + command);
        }
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).filter(value -> !value.isBlank()).toList();
    }

    private static boolean contains(String[] args, String value) {
        return Arrays.asList(args).contains(value);
    }

    private static String pretty(Object value, String description) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode " + description, ex);
        }
    }
}
