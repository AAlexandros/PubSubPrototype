package org.pubsub.prototype.util;

import java.util.HexFormat;

/** Shared hexadecimal encoding and SHA-256 value conversion. */
public final class HexUtils {
    private static final HexFormat HEX = HexFormat.of();

    private HexUtils() {
    }

    public static String normalizeSha256(String value, String field) {
        return Validators.normalizeSha256Hex(value, field);
    }

    public static byte[] decodeSha256(String value, String field) {
        return HEX.parseHex(normalizeSha256(value, field));
    }

    public static String encode(byte[] value) {
        return HEX.formatHex(value);
    }

    public static String zeroSha256() {
        return "0".repeat(CryptoConstants.SHA_256_BITS / 4);
    }
}
