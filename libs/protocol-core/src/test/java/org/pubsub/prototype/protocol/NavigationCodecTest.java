package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.navigation.NavigationExchange;
import org.pubsub.prototype.navigation.NavigationPeerDescriptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationCodecTest {
    @Test
    void roundTripsNavigationExchange() {
        var descriptor = new NavigationPeerDescriptor("a".repeat(64), "node-1", 7000, List.of("b".repeat(64)), 100);
        var exchange = new NavigationExchange(descriptor, List.of("b".repeat(64)), List.of(), 1);
        var codec = new ProtocolCodec();

        for (var type : List.of(MessageType.NAVIGATION_REQUEST, MessageType.NAVIGATION_RESPONSE)) {
            var message = ProtocolMessage.navigationGossip(type, UUID.randomUUID(), exchange);
            assertEquals(message, codec.decode(codec.encode(message)));
        }
    }

    @Test
    void rejectsMissingNavigationExchange() {
        var codec = new ProtocolCodec();

        assertThrows(ProtocolException.class,
                () -> codec.encode(ProtocolMessage.navigationGossip(MessageType.NAVIGATION_REQUEST, UUID.randomUUID(), null)));
    }
}
