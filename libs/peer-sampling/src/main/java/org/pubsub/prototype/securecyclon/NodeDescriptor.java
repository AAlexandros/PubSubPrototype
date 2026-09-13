package org.pubsub.prototype.securecyclon;

import org.pubsub.prototype.sampling.NodeEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Immutable SecureCyclon node descriptor: creation time, unique ID, ownership chain, and swap permission. */
public record NodeDescriptor(NodeEndpoint creator, long timestamp, String uniqueId,
                             List<String> ownershipChain, boolean swappable) {
    public NodeDescriptor { ownershipChain = List.copyOf(ownershipChain); }

    public static NodeDescriptor fresh(NodeEndpoint creator, long timestamp) {
        return new NodeDescriptor(creator, timestamp, id(creator.nodeId(), timestamp), List.of(creator.nodeId()), true);
    }

    public NodeDescriptor transfer(String receiver) {
        var chain = new ArrayList<>(ownershipChain);
        chain.add(receiver);
        return new NodeDescriptor(creator, timestamp, uniqueId, chain, true);
    }

    public NodeDescriptor retained() { return new NodeDescriptor(creator, timestamp, uniqueId, ownershipChain, false); }

    public static String id(String nodeId, long timestamp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((nodeId + ":" + timestamp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
