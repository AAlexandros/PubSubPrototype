package org.pubsub.prototype.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectBackoffTest {
    @Test
    void allowsTwoRetriesAndCanReset() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 5000);

        assertEquals(1000, backoff.nextDelayMs().orElseThrow());
        assertEquals(2000, backoff.nextDelayMs().orElseThrow());
        assertTrue(backoff.nextDelayMs().isEmpty());

        backoff.reset();
        assertEquals(1000, backoff.nextDelayMs().orElseThrow());
    }
}
