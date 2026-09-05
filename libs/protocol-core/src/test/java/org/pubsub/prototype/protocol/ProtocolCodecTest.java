package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {
    private final ProtocolCodec codec = new ProtocolCodec();
    private final NodeId nodeId = new NodeId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    @Test
    void encodesAndDecodesHello() {
        ProtocolMessage decoded = codec.decode(codec.encode(ProtocolMessage.hello(nodeId, "node-1")));

        assertEquals(MessageType.HELLO, decoded.type());
        assertEquals(nodeId.value(), decoded.nodeId());
        assertEquals("node-1", decoded.nodeName());
    }

    @Test
    void encodesAndDecodesPing() {
        UUID requestId = UUID.randomUUID();
        Instant sentAt = Instant.now();

        ProtocolMessage decoded = codec.decode(codec.encode(ProtocolMessage.ping(requestId, sentAt)));

        assertEquals(MessageType.PING, decoded.type());
        assertEquals(requestId, decoded.requestId());
        assertEquals(sentAt, decoded.sentAt());
    }

    @Test
    void rejectsUnsupportedVersion() {
        String json = """
                {"type":"PING","version":99,"requestId":"00000000-0000-0000-0000-000000000000","sentAt":"2026-08-25T00:00:00Z"}
                """;

        assertThrows(ProtocolException.class, () -> codec.decode(json.getBytes()));
    }

    @Test
    void rejectsIncompleteHello() {
        ProtocolMessage bad = new ProtocolMessage(MessageType.HELLO, ProtocolMessage.VERSION, nodeId.value(), null, null, null, null);

        assertThrows(ProtocolException.class, () -> codec.encode(bad));
    }
}
