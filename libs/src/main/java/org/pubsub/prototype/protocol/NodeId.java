package org.pubsub.prototype.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.HexFormat;

import static org.pubsub.prototype.util.CryptoConstants.SHA_256_ALGORITHM;
import static org.pubsub.prototype.util.ProtocolConstants.VALUE_FIELD;
import static org.pubsub.prototype.util.Validators.requireSha256Hex;

// Used to check that the id follows the sha-256 pattern.
// It is used for checking the node id value validity, similarly to DTOs
public record NodeId(String value) {
    public NodeId {
        requireSha256Hex(value, VALUE_FIELD);
    }

    public static NodeId fromPublicKey(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            return new NodeId(HexFormat.of().formatHex(digest.digest(publicKey.getEncoded())));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(String.format("%s is not available", SHA_256_ALGORITHM), ex);
        }
    }
}
