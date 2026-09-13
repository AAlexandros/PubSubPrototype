package org.pubsub.prototype.persistence;

import java.util.HexFormat;

public final class PersistenceHex {
    private static final HexFormat HEX = HexFormat.of();

    private PersistenceHex() {
    }

    public static String require256(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be a 256-bit hexadecimal value");
        }
        return value.toLowerCase();
    }

    public static byte[] decode256(String value, String field) {
        return HEX.parseHex(require256(value, field));
    }

    public static String encode(byte[] value) {
        return HEX.formatHex(value);
    }
}
