package org.pubsub.prototype.protocol;

public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
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
}
