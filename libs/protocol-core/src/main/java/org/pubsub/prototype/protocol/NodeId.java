package org.pubsub.prototype.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Objects;
import java.util.regex.Pattern;

public record NodeId(String value) {
    private static final Pattern LOWERCASE_SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public NodeId {
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("NodeId must be lowercase SHA-256 hex");
        }
    }

    public static NodeId fromPublicKey(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new NodeId(Hex.encode(digest.digest(publicKey.getEncoded())));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
