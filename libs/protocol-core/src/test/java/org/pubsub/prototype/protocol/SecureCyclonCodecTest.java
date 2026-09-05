package org.pubsub.prototype.protocol;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.securecyclon.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureCyclonCodecTest {
    @Test void roundTripsExchangesAndAdvertisedEndpoint() {
        var peer = new PeerDescriptor("a".repeat(64), "node-1", 7000);
        var exchange = new Exchange(List.of(SecureLink.fresh(peer, 100).transfer("b".repeat(64))), List.of());
        var codec = new ProtocolCodec();
        for (var type : List.of(MessageType.SECURECYCLON_REQUEST, MessageType.SECURECYCLON_RESPONSE)) {
            var message = ProtocolMessage.gossip(type, UUID.randomUUID(), exchange);
            assertEquals(message, codec.decode(codec.encode(message)));
        }
        var hello = ProtocolMessage.hello(new NodeId(peer.nodeId()), "node-1").withDescriptor(peer);
        assertEquals(hello, codec.decode(codec.encode(hello)));
    }
    @Test void rejectsMissingBodyInvalidEndpointAndWrongVersion() {
        var codec = new ProtocolCodec();
        assertThrows(ProtocolException.class, () -> codec.encode(ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, UUID.randomUUID(), null)));
        assertThrows(ProtocolException.class, () -> codec.decode("{\"type\":\"SECURECYCLON_REQUEST\",\"version\":99}".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> new PeerDescriptor("a".repeat(64), "0.0.0.0", 7000));
        assertThrows(IllegalArgumentException.class, () -> new PeerDescriptor("a".repeat(64), "node", 65536));
    }
}
