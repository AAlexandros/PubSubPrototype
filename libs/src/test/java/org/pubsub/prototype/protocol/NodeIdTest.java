package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeIdTest {
    @Test
    void derivesStableLowercaseSha256HexFromPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = generator.generateKeyPair();

        NodeId first = NodeId.fromPublicKey(keyPair.getPublic());
        NodeId second = NodeId.fromPublicKey(keyPair.getPublic());

        assertEquals(first, second);
        assertEquals(64, first.value().length());
        assertEquals(first.value().toLowerCase(), first.value());
    }

    @Test
    void rejectsInvalidNodeId() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId("ABC"));
    }
}
