package org.pubsub.prototype.cardano.devnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevnetLayoutTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsHostDevnetPathsIntoTheContainer() throws IOException {
        Files.writeString(tempDir.resolve("compose.yaml"), "services: {}\n");
        Files.writeString(tempDir.resolve("versions.env"), "CARDANO_NETWORK_MAGIC=42\n");
        DevnetLayout layout = new DevnetLayout(tempDir);

        assertEquals("/devnet/runtime/registry/topic.json",
                layout.containerPath(tempDir.resolve("runtime/registry/topic.json").toString()));
        assertEquals("--output-json", layout.containerPath("--output-json"));
        assertEquals("CARDANO_NODE_SOCKET_PATH=/devnet/runtime/testnet/socket/node1/sock",
                layout.socketEnvironment());
    }

    @Test
    void requiresTheComposeAndVersionFiles() {
        assertThrows(IllegalArgumentException.class, () -> new DevnetLayout(tempDir));
    }
}
