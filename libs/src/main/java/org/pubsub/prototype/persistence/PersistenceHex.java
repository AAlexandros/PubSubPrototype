package org.pubsub.prototype.persistence;

import java.util.HexFormat;

import static org.pubsub.prototype.util.Validators.normalizeSha256Hex;

public final class PersistenceHex {
    private static final HexFormat HEX = HexFormat.of();

    private PersistenceHex() {
    }

    public static String require256(String value, String field) {
        return normalizeSha256Hex(value, field);
    }

    public static byte[] decode256(String value, String field) {
        return HEX.parseHex(require256(value, field));
    }

    public static String encode(byte[] value) {
        return HEX.formatHex(value);
    }
}
