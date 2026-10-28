package org.pubsub.prototype.util.validation;

import org.pubsub.prototype.constants.NetworkConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds a set of validation checks and collects every failed check instead of stopping at the
 * first one. Use it when an object has several independent rules and callers should see all
 * violations together. Use {@link Validators} for a single check that should fail immediately.
 *
 * <p>Example: {@code Validation.start().required(id, "id").positive(count, "count")
 * .throwIfInvalid()}.</p>
 */
public final class Validation {
    private final List<String> violations = new ArrayList<>();

    /** Starts a new set of validation checks. */
    public static Validation start() {
        return new Validation();
    }

    /** Adds a failure when {@code condition} is false. */
    public Validation require(boolean condition, String message) {
        if (!condition) violations.add(message);
        return this;
    }

    /** Adds a failure when the value is null. */
    public Validation required(Object value, String field) {
        return require(value != null, field + " is required");
    }

    /** Adds a failure when the value is null or blank. */
    public Validation nonBlank(String value, String field) {
        return require(value != null && !value.isBlank(), field + " is required");
    }

    /** Adds a failure when the value is null or not greater than zero. */
    public Validation positive(Number value, String field) {
        return require(value != null && value.longValue() > 0, field + " must be greater than zero");
    }

    /** Adds a failure when the value is null or less than zero. */
    public Validation nonNegative(Number value, String field) {
        return require(value != null && value.longValue() >= 0, field + " must be non-negative");
    }

    /** Adds a failure when the value is null or outside the inclusive range. */
    public Validation range(Number value, long minimum, long maximum, String field) {
        return require(value != null && value.longValue() >= minimum && value.longValue() <= maximum,
                field + " must be between " + minimum + " and " + maximum);
    }

    /** Adds a failure when the port is outside the supported network port range. */
    public Validation port(Number value, String field) {
        return range(value, NetworkConstants.MIN_PORT, NetworkConstants.MAX_PORT, field);
    }

    /** Adds a failure when the value is null or does not match the complete pattern. */
    public Validation matches(String value, Pattern pattern, String field, String expectation) {
        return require(value != null && pattern.matcher(value).matches(), field + " " + expectation);
    }

    /** Returns whether all checks added so far have passed. */
    public boolean isValid() {
        return violations.isEmpty();
    }

    /** Returns an immutable copy of the failed-check messages. */
    public List<String> violations() {
        return List.copyOf(violations);
    }

    /** Throws one {@link ValidationException} containing all failed-check messages, if any. */
    public void throwIfInvalid() {
        if (!isValid()) throw new ValidationException(violations);
    }

    /** Throws if any check failed; otherwise returns the supplied value unchanged. */
    public <T> T validate(T value) {
        throwIfInvalid();
        return value;
    }

    private Validation() {
    }
}
