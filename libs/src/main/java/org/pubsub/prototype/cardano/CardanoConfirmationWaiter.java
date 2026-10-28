package org.pubsub.prototype.cardano;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls for a submitted registry transaction to become visible on Cardano. */
public final class CardanoConfirmationWaiter {
    private static final int MAX_ATTEMPTS = 60;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private CardanoConfirmationWaiter() {
    }

    public static void await(BooleanSupplier confirmed, RuntimeException timeout) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (confirmed.getAsBoolean()) {
                return;
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Cardano registry confirmation", ex);
                }
            }
        }
        throw timeout;
    }
}
