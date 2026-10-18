package org.pubsub.prototype.replication.registry.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.registry.replication.FileReplicationRegistry;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** CLI-only support for deriving a server ID from a Cardano verification-key file. */
final class ReplicationRegistryCliSupport {
    private ReplicationRegistryCliSupport() {
    }

    static String serverId(Path verificationKey) {
        try {
            JsonNode node = JsonSupport.MAPPER.readTree(Files.readAllBytes(verificationKey));
            String cborHex = node.path("cborHex").asText();
            if (cborHex.isBlank()) throw new IllegalArgumentException("verification key has no cborHex");
            return FileReplicationRegistry.serverId(HexFormat.of().parseHex(cborHex));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read verification key " + verificationKey, ex);
        }
    }
}
