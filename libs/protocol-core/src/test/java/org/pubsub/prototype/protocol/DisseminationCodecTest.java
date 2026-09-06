package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.dissemination.DisseminationEngine;
import org.pubsub.prototype.dissemination.DisseminationExchange;
import org.pubsub.prototype.dissemination.DisseminationPeerDescriptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisseminationCodecTest {
    @Test
    void requestAndResponseRoundTrip() {
        DisseminationPeerDescriptor sender = new DisseminationPeerDescriptor(
                "1".repeat(64), "node-1", 7000, List.of("a".repeat(64)), 123);
        DisseminationExchange exchange = new DisseminationExchange(
                "a".repeat(64), sender, List.of(), DisseminationEngine.PROTOCOL_VERSION);
        ProtocolCodec codec = new ProtocolCodec();

        for (MessageType type : List.of(MessageType.DISSEMINATION_REQUEST, MessageType.DISSEMINATION_RESPONSE)) {
            ProtocolMessage message = ProtocolMessage.disseminationGossip(type, UUID.randomUUID(), exchange);
            assertEquals(message, codec.decode(codec.encode(message)));
        }
    }

    @Test
    void rejectsMissingExchange() {
        ProtocolMessage invalid = ProtocolMessage.disseminationGossip(
                MessageType.DISSEMINATION_REQUEST, UUID.randomUUID(), null);
        assertThrows(ProtocolException.class, () -> new ProtocolCodec().encode(invalid));
    }
}
