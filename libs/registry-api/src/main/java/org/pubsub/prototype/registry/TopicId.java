package org.pubsub.prototype.registry;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record TopicId(String value) {
    private static final Pattern HEX_256 = Pattern.compile("[0-9a-f]{64}");

    public TopicId {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!HEX_256.matcher(value).matches()) {
            throw new IllegalArgumentException("topicId must be a 256-bit lowercase hex value");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
