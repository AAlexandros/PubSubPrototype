package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.PeerDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Immutable equivalent of PeerNet CyclonPeer: creation time, link ID, ownership and swap permission. */
public record SecureLink(PeerDescriptor peer, long timestamp, String uniqueId, List<String> owners, boolean swappable) {
    public SecureLink { owners = List.copyOf(owners); }

    public static SecureLink fresh(PeerDescriptor peer, long timestamp) {
        return new SecureLink(peer, timestamp, id(peer.nodeId(), timestamp), List.of(peer.nodeId()), true);
    }

    public SecureLink transfer(String receiver) {
        var chain = new ArrayList<>(owners);
        chain.add(receiver);
        return new SecureLink(peer, timestamp, uniqueId, chain, true);
    }

    public SecureLink retained() { return new SecureLink(peer, timestamp, uniqueId, owners, false); }

    public static String id(String nodeId, long timestamp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((nodeId + ":" + timestamp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
