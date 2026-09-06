package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionStoreTest {
    @TempDir
    Path dir;

    @Test
    void persistsAcrossReload() {
        Path file = dir.resolve("subscriptions.txt");
        SubscriptionStore store = SubscriptionStore.loadOrCreate(file);
        store.add("a".repeat(64));
        store.add("b".repeat(64));
        store.remove("a".repeat(64));

        SubscriptionStore reloaded = SubscriptionStore.loadOrCreate(file);

        assertEquals(reloaded.snapshot(), store.snapshot());
        assertTrue(reloaded.snapshot().contains("b".repeat(64)));
        assertTrue(!reloaded.snapshot().contains("a".repeat(64)));
    }

    @Test
    void startsEmptyWhenNoFileExists() {
        SubscriptionStore store = SubscriptionStore.loadOrCreate(dir.resolve("subscriptions.txt"));

        assertTrue(store.snapshot().isEmpty());
    }
}
