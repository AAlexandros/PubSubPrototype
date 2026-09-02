package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.CreateTopicRequest;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class RegistryCli {
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
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: RegistryCli <runtime-dir> <command> ...");
        }
        Path runtimeDir = Path.of(args[0]);
        String command = args[1];
        String signer = args.length > 2 ? args[2] : "registry-deployer";
        CardanoTopicRegistry registry = new CardanoTopicRegistry(new CardanoRegistryConfig(runtimeDir, signer));
        TopicDatumCodec codec = new TopicDatumCodec();

        switch (command) {
            case "deploy" -> {
                registry.deploy();
                System.out.println("REGISTRY_DEPLOYED");
            }
            case "query" -> {
                boolean all = contains(args, "--all");
                System.out.println(codec.encodeSnapshot(all ? registry.snapshotIncludingTombstones() : registry.snapshot()));
            }
            case "utxos" -> System.out.println(registry.scriptUtxosJson());
            case "topic" -> {
                TopicId topicId = new TopicId(args[3]);
                TopicState topic = registry.topic(topicId).orElseThrow(() -> new IllegalArgumentException("unknown topicId"));
                System.out.println(codec.encode(topic));
            }
            case "create" -> {
                TopicId topicId = registry.createTopic(new CreateTopicRequest(
                        args[3],
                        csv(args[4]),
                        csv(args[5]),
                        Integer.parseInt(args[6]),
                        Long.parseLong(args[7])
                ));
                System.out.println(topicId);
            }
            case "delete" -> registry.deleteTopic(new TopicId(args[3]));
            case "add-owner" -> registry.addOwner(new TopicId(args[3]), args[4]);
            case "remove-owner" -> registry.removeOwner(new TopicId(args[3]), args[4]);
            case "add-admin" -> registry.addAdmin(new TopicId(args[3]), args[4]);
            case "remove-admin" -> registry.removeAdmin(new TopicId(args[3]), args[4]);
            case "add-publisher" -> registry.addPublisher(new TopicId(args[3]), args[4]);
            case "remove-publisher" -> registry.removePublisher(new TopicId(args[3]), args[4]);
            case "set-replication-factor" -> registry.setReplicationFactor(new TopicId(args[3]), Integer.parseInt(args[4]));
            case "set-retention-period" -> registry.setRetentionPeriod(new TopicId(args[3]), Long.parseLong(args[4]));
            case "direct-last-owner-removal" -> registry.buildInvalidLastOwnerRemoval(new TopicId(args[3]), args[4]);
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
}
