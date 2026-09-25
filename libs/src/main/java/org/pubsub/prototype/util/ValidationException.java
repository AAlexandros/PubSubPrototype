package org.pubsub.prototype.util;

import java.util.List;

/** Thrown when one or more declarative validation rules fail. */
public final class ValidationException extends IllegalArgumentException {
    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super(String.join("; ", List.copyOf(violations)));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
