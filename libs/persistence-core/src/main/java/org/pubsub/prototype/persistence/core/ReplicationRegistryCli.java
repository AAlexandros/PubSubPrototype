package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.persistence.ReplicationServerState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

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
        if (args.length < 2) throw new IllegalArgumentException("Usage: ReplicationRegistryCli <state-file> <command> ...");
        FileReplicationRegistry registry = new FileReplicationRegistry(Path.of(args[0]));
        switch (args[1]) {
            case "server-id" -> System.out.println(serverId(Path.of(args[2])));
            case "register" -> {
                ReplicationServerState server = new ReplicationServerState(args[2], args[3], args[4],
                        Integer.parseInt(args[5]), Long.parseLong(args[6]), Long.parseLong(args[7]), true);
                System.out.println(PersistenceJson.MAPPER.valueToTree(registry.registerServer(server, args[3])));
            }
            case "unregister" -> registry.unregisterServer(args[2], args[3]);
            case "query" -> System.out.println(PersistenceJson.MAPPER.valueToTree(
                    registry.queryServers(args.length > 2 && "--all".equals(args[2]))));
            default -> throw new IllegalArgumentException("Unsupported command: " + args[1]);
        }
    }

    static String serverId(Path verificationKey) {
        try {
            JsonNode node = PersistenceJson.MAPPER.readTree(Files.readAllBytes(verificationKey));
            String cborHex = node.path("cborHex").asText();
            if (cborHex.isBlank()) throw new IllegalArgumentException("verification key has no cborHex");
            return FileReplicationRegistry.serverId(HexFormat.of().parseHex(cborHex));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read verification key " + verificationKey, ex);
        }
    }
}
