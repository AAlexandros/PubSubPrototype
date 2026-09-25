package org.pubsub.prototype.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Fail-fast primitive validators intended for record constructors and method arguments. */
public final class Validators {
    private static final Pattern SHA_256 = Pattern.compile(CryptoConstants.SHA_256_HEX_PATTERN);
    private static final Pattern HOST = Pattern.compile(NetworkConstants.HOST_PATTERN);

    public static boolean isSha256Hex(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    public static boolean isAdvertisedEndpoint(String host, int port) {
        return host != null && !host.isBlank() && host.length() <= NetworkConstants.MAX_HOST_LENGTH
                && HOST.matcher(host).matches()
                && !NetworkConstants.UNSPECIFIED_IPV4_ADDRESS.equals(host)
                && !NetworkConstants.UNSPECIFIED_IPV6_ADDRESS.equals(host)
                && port >= NetworkConstants.MIN_PORT && port <= NetworkConstants.MAX_PORT;
    }

    public static <T> T requireNonNull(T value, String field) {
        return Validation.start().required(value, field).validate(value);
    }

    public static String requireNonBlank(String value, String field) {
        return Validation.start().nonBlank(value, field).validate(value);
    }

    public static String requireSha256Hex(String value, String field) {
        return Validation.start()
                .matches(value, SHA_256, field, "must be lowercase SHA-256 hex")
                .validate(value);
    }

    public static String normalizeSha256Hex(String value, String field) {
        requireNonNull(value, field);
        return requireSha256Hex(value.toLowerCase(Locale.ROOT), field);
    }

    public static int requirePort(Integer value, String field) {
        return Validation.start().port(value, field).validate(value);
    }

    public static int requirePositive(Integer value, String field) {
        return Validation.start().positive(value, field).validate(value);
    }

    public static long requirePositive(Long value, String field) {
        return Validation.start().positive(value, field).validate(value);
    }

    public static int requireNonNegative(Integer value, String field) {
        return Validation.start().nonNegative(value, field).validate(value);
    }

    public static long requireNonNegative(Long value, String field) {
        return Validation.start().nonNegative(value, field).validate(value);
    }

    public static int requireRange(Integer value, int minimum, int maximum, String field) {
        return Validation.start().range(value, minimum, maximum, field).validate(value);
    }

    public static void require(boolean condition, String message) {
        Validation.start().require(condition, message).throwIfInvalid();
    }

    private Validators() {
    }
}
