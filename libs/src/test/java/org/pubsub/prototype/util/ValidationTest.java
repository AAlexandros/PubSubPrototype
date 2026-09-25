package org.pubsub.prototype.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationTest {
    @Test
    void fluentValidationCollectsViolations() {
        ValidationException exception = assertThrows(ValidationException.class, () -> Validation.start()
                .nonBlank("", "name")
                .port(70_000, "port")
                .throwIfInvalid());

        assertEquals(java.util.List.of(
                "name is required",
                "port must be between 1 and 65535"), exception.violations());
    }

    @Test
    void primitiveValidatorsReturnValidatedAndNormalizedValues() {
        assertEquals(7000, Validators.requirePort(7000, "port"));
        assertEquals("a".repeat(64), Validators.normalizeSha256Hex("A".repeat(64), "id"));
    }

    @Test
    void primitiveValidatorsAreNullSafe() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Validators.requirePositive((Integer) null, "count"));

        assertEquals("count must be greater than zero", exception.getMessage());
    }
}
