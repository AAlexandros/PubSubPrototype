package org.pubsub.prototype.registry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryObserverTest {
    @Test
    void firstReadFailureRetainsNoAuthorityAndOutageExpiresLastValidView() {
        MutableClock clock = new MutableClock();
        AtomicReference<List<String>> chain = new AtomicReference<>();
        try (var observer = new RegistryObserver<>("topic", "node-1", () -> {
            List<String> value = chain.get();
            if (value == null) throw new IllegalStateException("socket unavailable");
            return List.copyOf(value);
        }, 1000, 3000, clock)) {
            observer.poll();
            assertEquals(RegistryObserver.Freshness.NOT_LOADED, observer.freshness());
            assertThrows(RegistryUnavailableException.class, observer::requireFresh);

            chain.set(List.of("topic-a"));
            observer.poll();
            assertEquals(List.of("topic-a"), observer.requireFresh());
            chain.set(null);
            clock.advanceMillis(2000);
            observer.poll();
            assertEquals(List.of("topic-a"), observer.requireFresh());
            clock.advanceMillis(1001);
            assertEquals(RegistryObserver.Freshness.STALE, observer.freshness());
            assertEquals(List.of("topic-a"), observer.lastObserved().orElseThrow());
            assertThrows(RegistryUnavailableException.class, observer::requireFresh);

            chain.set(List.of());
            observer.poll();
            assertEquals(RegistryObserver.Freshness.FRESH, observer.freshness());
            assertEquals(List.of(), observer.requireFresh());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void advanceMillis(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
