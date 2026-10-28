package org.pubsub.prototype.util.validation;

import org.pubsub.prototype.constants.CryptoConstants;
import org.pubsub.prototype.constants.NetworkConstants;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stateless, fail-fast checks for one value or condition, commonly used in record constructors
 * and method arguments. Methods named {@code require...} throw {@link ValidationException} when
 * the check fails; {@code is...} methods return a boolean. Use {@link Validation} to collect
 * several failures and report them together.
 */
public final class Validators {
    private static final Pattern SHA_256 = Pattern.compile(CryptoConstants.SHA_256_HEX_PATTERN);
    private static final Pattern HOST = Pattern.compile(NetworkConstants.HOST_PATTERN);

    /** Returns whether the value is lowercase hexadecimal encoding of a SHA-256 digest. */
    public static boolean isSha256Hex(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    /** Returns whether the host and port form an allowed advertised endpoint. */
    public static boolean isAdvertisedEndpoint(String host, int port) {
        return host != null && !host.isBlank() && host.length() <= NetworkConstants.MAX_HOST_LENGTH
                && HOST.matcher(host).matches()
                && !NetworkConstants.UNSPECIFIED_IPV4_ADDRESS.equals(host)
                && !NetworkConstants.UNSPECIFIED_IPV6_ADDRESS.equals(host)
                && port >= NetworkConstants.MIN_PORT && port <= NetworkConstants.MAX_PORT;
    }

    /** Requires a non-null value and returns it unchanged. */
    public static <T> T requireNonNull(T value, String field) {
        return Validation.start().required(value, field).validate(value);
    }

    /** Requires a non-null, non-blank string and returns it unchanged. */
    public static String requireNonBlank(String value, String field) {
        return Validation.start().nonBlank(value, field).validate(value);
    }

    /** Requires lowercase SHA-256 hex and returns the value unchanged. */
    public static String requireSha256Hex(String value, String field) {
        return Validation.start()
                .matches(value, SHA_256, field, "must be lowercase SHA-256 hex")
                .validate(value);
    }

    /** Lowercases the value, then requires it to be valid SHA-256 hex. */
    public static String normalizeSha256Hex(String value, String field) {
        requireNonNull(value, field);
        return requireSha256Hex(value.toLowerCase(Locale.ROOT), field);
    }

    /** Requires a port within the supported network range and returns it. */
    public static int requirePort(Integer value, String field) {
        return Validation.start().port(value, field).validate(value);
    }

    /** Requires an integer greater than zero and returns it. */
    public static int requirePositive(Integer value, String field) {
        return Validation.start().positive(value, field).validate(value);
    }

    /** Requires a long greater than zero and returns it. */
    public static long requirePositive(Long value, String field) {
        return Validation.start().positive(value, field).validate(value);
    }

    /** Requires a non-negative integer and returns it. */
    public static int requireNonNegative(Integer value, String field) {
        return Validation.start().nonNegative(value, field).validate(value);
    }

    /** Requires a non-negative long and returns it. */
    public static long requireNonNegative(Long value, String field) {
        return Validation.start().nonNegative(value, field).validate(value);
    }

    /** Requires the integer to be within the inclusive range and returns it. */
    public static int requireRange(Integer value, int minimum, int maximum, String field) {
        return Validation.start().range(value, minimum, maximum, field).validate(value);
    }

    /** Throws when the condition is false. */
    public static void require(boolean condition, String message) {
        Validation.start().require(condition, message).throwIfInvalid();
    }

    private Validators() {
    }
}
