package org.pubsub.prototype.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconnectBackoffTest {
    @Test
    void doublesUntilMaximumAndCanReset() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 5000);

        assertEquals(1000, backoff.nextDelayMs());
        assertEquals(2000, backoff.nextDelayMs());
        assertEquals(4000, backoff.nextDelayMs());
        assertEquals(5000, backoff.nextDelayMs());
        assertEquals(5000, backoff.nextDelayMs());

        backoff.reset();
        assertEquals(1000, backoff.nextDelayMs());
    }
}
