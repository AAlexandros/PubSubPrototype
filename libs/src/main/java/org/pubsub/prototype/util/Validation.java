package org.pubsub.prototype.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fluent, null-safe validation for objects with several independent rules.
 * Violations are collected and reported together by {@link #throwIfInvalid()}.
 */
public final class Validation {
    private final List<String> violations = new ArrayList<>();

    public static Validation start() {
        return new Validation();
    }

    public Validation require(boolean condition, String message) {
        if (!condition) violations.add(message);
        return this;
    }

    public Validation required(Object value, String field) {
        return require(value != null, field + " is required");
    }

    public Validation nonBlank(String value, String field) {
        return require(value != null && !value.isBlank(), field + " is required");
    }

    public Validation positive(Number value, String field) {
        return require(value != null && value.longValue() > 0, field + " must be greater than zero");
    }

    public Validation nonNegative(Number value, String field) {
        return require(value != null && value.longValue() >= 0, field + " must be non-negative");
    }

    public Validation range(Number value, long minimum, long maximum, String field) {
        return require(value != null && value.longValue() >= minimum && value.longValue() <= maximum,
                field + " must be between " + minimum + " and " + maximum);
    }

    public Validation port(Number value, String field) {
        return range(value, NetworkConstants.MIN_PORT, NetworkConstants.MAX_PORT, field);
    }

    public Validation matches(String value, Pattern pattern, String field, String expectation) {
        return require(value != null && pattern.matcher(value).matches(), field + " " + expectation);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }

    public void throwIfInvalid() {
        if (!isValid()) throw new ValidationException(violations);
    }

    public <T> T validate(T value) {
        throwIfInvalid();
        return value;
    }

    private Validation() {
    }
}
