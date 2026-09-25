package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.NodeEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.pubsub.prototype.util.CryptoConstants.SHA_256_ALGORITHM;

/** Immutable SecureCyclon node descriptor: creation time, unique ID, ownership chain, and swap permission. */
public record NodeDescriptor(NodeEndpoint creator, long timestamp, String uniqueId,
                             List<String> ownershipChain, boolean swappable) {
    public NodeDescriptor { ownershipChain = List.copyOf(ownershipChain); }

    public static NodeDescriptor fresh(NodeEndpoint creator, long timestamp) {
        return new NodeDescriptor(creator, timestamp, id(creator.nodeId(), timestamp), List.of(creator.nodeId()), true);
    }

    // Transfering a descriptor means that the receiving node is added as the last owner in the chain of ownership
    public NodeDescriptor transfer(String receiver) {
        var chain = new ArrayList<>(ownershipChain);
        chain.add(receiver);
        return new NodeDescriptor(creator, timestamp, uniqueId, chain, true);
    }

    // Called when a node wants to retain a sent descriptor, marking it as non-swappable.
    public NodeDescriptor retained() { return new NodeDescriptor(creator, timestamp, uniqueId, ownershipChain, false); }

    // Each link contains a unique identifier, that is a hash of the timestamp at the creation time concatenated by the links address
    public static String id(String nodeId, long timestamp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256_ALGORITHM)
                    .digest((nodeId + ":" + timestamp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
