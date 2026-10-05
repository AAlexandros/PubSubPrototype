package org.pubsub.prototype.registry;

import static org.pubsub.prototype.util.SharedFieldNames.VALUE;
import static org.pubsub.prototype.util.Validators.normalizeSha256Hex;

/**
 * A type-safe topic identifier that validates and normalizes a SHA-256 hexadecimal value.
 */
public record TopicId(String value) {
    public TopicId {
        value = normalizeSha256Hex(value, VALUE);
    }

    @Override
    public String toString() {
        return value;
    }
}
