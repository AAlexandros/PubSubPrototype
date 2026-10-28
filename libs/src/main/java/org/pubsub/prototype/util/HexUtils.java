package org.pubsub.prototype.util;

import org.pubsub.prototype.constants.CryptoConstants;
import org.pubsub.prototype.util.validation.Validators;

import java.util.HexFormat;

/** Converts between hexadecimal strings and bytes, with helpers for SHA-256 values. */
public final class HexUtils {
    private static final HexFormat HEX = HexFormat.of();

    private HexUtils() {
    }

    /** Converts a SHA-256 hex value to lowercase and rejects values with an invalid format.
     *
     * @param value the SHA-256 value to normalize
     * @param field the field name to include in validation errors
     * @return the validated value in lowercase
     * @throws IllegalArgumentException if the value is null or is not 64 hexadecimal characters
     */
    public static String normalizeSha256(String value, String field) {
        return Validators.normalizeSha256Hex(value, field);
    }

    /**
     * Normalizes and validates a SHA-256 hex value, then decodes it into its 32 bytes.
     *
     * @param value the SHA-256 value to decode
     * @param field the field name to include in validation errors
     * @return the decoded digest bytes
     * @throws IllegalArgumentException if the value is null or is not 64 hexadecimal characters
     */
    public static byte[] decodeSha256(String value, String field) {
        return HEX.parseHex(normalizeSha256(value, field));
    }

    /** Encodes bytes as lowercase hexadecimal characters. */
    public static String encode(byte[] value) {
        return HEX.formatHex(value);
    }

    /** Returns a lowercase hexadecimal string containing 32 zero bytes. */
    public static String zeroSha256() {
        return "0".repeat(CryptoConstants.SHA_256_BITS / 4);
    }
}
