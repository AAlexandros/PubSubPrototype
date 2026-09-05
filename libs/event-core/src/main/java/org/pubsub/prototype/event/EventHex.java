package org.pubsub.prototype.event;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EventHex {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private EventHex() {
    }

    public static String encode(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = DIGITS[value >>> 4];
            encoded[i * 2 + 1] = DIGITS[value & 0x0f];
        }
        return new String(encoded);
    }

    public static byte[] decode(String hex) {
        requireSha256(hex, "hex");
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    public static String normalizeSha256(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        requireSha256(normalized, field);
        return normalized;
    }

    public static void requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }

    public static String zeroSha256() {
        return "0".repeat(64);
    }
}
