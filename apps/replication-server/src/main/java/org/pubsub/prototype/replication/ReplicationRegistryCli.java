package org.pubsub.prototype.replication;

import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.persistence.core.FileReplicationRegistry;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistry;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryConfig;
import org.pubsub.prototype.util.JsonSupport;

import java.nio.file.Path;

/** CLI for deploying, querying, and changing the Cardano replication registry. */
public final class ReplicationRegistryCli {
    private ReplicationRegistryCli() {
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
        if (args.length == 0) throw usage();
        switch (args[0]) {
            case "server-id" -> {
                requireLength(args, 2);
                System.out.println(ReplicationRegistryCliSupport.serverId(Path.of(args[1])));
            }
            case "deploy" -> {
                requireLength(args, 3);
                var registry = registry(Path.of(args[1]), CardanoCliBackend.parse(args[2]), "registry-deployer");
                System.out.println("REPLICATION_REGISTRY_DEPLOYED validatorAddress="
                        + registry.deploy().validatorAddress());
            }
            case "query" -> {
                if (args.length < 3 || args.length > 4 || args.length == 4 && !"--all".equals(args[3])) {
                    throw usage();
                }
                var registry = registry(Path.of(args[1]), CardanoCliBackend.parse(args[2]), "registry-deployer");
                System.out.println(JsonSupport.MAPPER.valueToTree(registry.queryServers(args.length == 4)).toPrettyString());
            }
            case "register", "unregister" -> registration(args);
            default -> throw usage();
        }
    }

    private static void registration(String[] args) {
        requireLength(args, 3);
        ReplicationServerConfig server = ReplicationServerConfig.load(Path.of(args[1]));
        CardanoReplicationRegistry registry = registry(Path.of(args[2]),
                server.registration().cliBackend(), server.registration().operator());
        if ("register".equals(args[0])) {
            ReplicationServerState state = new ReplicationServerState(
                    server.serverId(), registry.configuredOperatorId(), server.advertisedHost(), server.port(),
                    server.registration().commitmentStartEpoch(), server.registration().commitmentEndEpoch(), true);
            System.out.println(JsonSupport.MAPPER.valueToTree(registry.registerServer(state)));
        } else {
            registry.unregisterServer(server.serverId());
        }
    }

    private static CardanoReplicationRegistry registry(Path runtimeDir, CardanoCliBackend backend, String signer) {
        return new CardanoReplicationRegistry(new CardanoReplicationRegistryConfig(runtimeDir, signer, backend));
    }

    private static void requireLength(String[] args, int length) {
        if (args.length != length) throw usage();
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: ReplicationRegistryCli server-id <verification-key> | "
                + "deploy <runtime-dir> <backend> | query <runtime-dir> <backend> [--all] | "
                + "register|unregister <replication-server-config> <runtime-dir>");
    }
}
