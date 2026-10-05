package org.pubsub.prototype.util;

/** Cryptographic names and formats shared across protocol modules. */
public final class CryptoConstants {
    public static final int SHA_256_BITS = 256;
    public static final String SHA_256_ALGORITHM = "SHA-256";
    public static final String ED25519_ALGORITHM = "Ed25519";
    public static final String HEX_CASE_INSENSITIVE_PATTERN = "[0-9a-fA-F]*";
    public static final String SHA_256_HEX_PATTERN = "[0-9a-f]{64}";
    public static final String SHA_256_HEX_CASE_INSENSITIVE_PATTERN = "[0-9a-fA-F]{64}";

    private CryptoConstants() {
    }
}
