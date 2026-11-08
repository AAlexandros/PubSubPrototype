package org.pubsub.prototype.replication.registry.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.registry.replication.ReplicationServerState;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistry;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryConfig;
import org.pubsub.prototype.replication.config.ReplicationServerConfig;
import org.pubsub.prototype.util.files.JsonSupport;

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
                ArrayNode states = JsonSupport.MAPPER.createArrayNode();
                registry.queryServers(args.length == 4).forEach(state -> states.add(stateJson(state)));
                System.out.println(states.toPrettyString());
            }
            case "register", "unregister" -> registration(args);
            default -> throw usage();
        }
    }

    private static void registration(String[] args) {
        requireLength(args, 3);
        ReplicationServerConfig.RegistryRegistration server =
                ReplicationServerConfig.loadRegistryRegistration(Path.of(args[1]));
        CardanoReplicationRegistry registry = registry(Path.of(args[2]),
                server.registration().cliBackend(), server.registration().operator());
        if ("register".equals(args[0])) {
            ReplicationServerState state = new ReplicationServerState(
                    new ReplicationServerEndpoint(server.serverId(), server.advertisedHost(), server.port()),
                    registry.configuredOperatorId(),
                    server.registration().commitmentStartEpoch(), server.registration().commitmentEndEpoch(), true);
            System.out.println(stateJson(registry.registerServer(state)));
        } else {
            registry.unregisterServer(server.serverId());
        }
    }

    private static CardanoReplicationRegistry registry(Path runtimeDir, CardanoCliBackend backend, String signer) {
        return new CardanoReplicationRegistry(new CardanoReplicationRegistryConfig(runtimeDir, signer, backend));
    }

    /** Keeps the CLI's flat JSON output while the domain state owns an endpoint value. */
    private static ObjectNode stateJson(ReplicationServerState state) {
        ObjectNode result = JsonSupport.MAPPER.createObjectNode();
        result.put("serverId", state.serverId());
        result.put("operator", state.operator());
        result.put("host", state.host());
        result.put("port", state.port());
        result.put("commitmentStartEpoch", state.commitmentStartEpoch());
        result.put("commitmentEndEpoch", state.commitmentEndEpoch());
        result.put("active", state.active());
        return result;
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
