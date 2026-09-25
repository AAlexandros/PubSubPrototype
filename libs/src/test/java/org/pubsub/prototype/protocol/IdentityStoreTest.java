package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void reusesPersistedIdentity() {
        NodeIdentity first = IdentityStore.loadOrCreate(tempDir);
        NodeIdentity second = IdentityStore.loadOrCreate(tempDir);

        assertEquals(first.nodeId(), second.nodeId());
        assertTrue(tempDir.resolve("identity.json").toFile().isFile());
    }
}
