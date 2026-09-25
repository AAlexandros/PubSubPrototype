package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionStoreTest {
    @TempDir
    Path dir;

    @Test
    void persistsAcrossReload() {
        Path file = dir.resolve("subscriptions.json");
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
    void startsEmptyWhenNoFileExists() throws IOException {
        Path file = dir.resolve("subscriptions.json");
        SubscriptionStore store = SubscriptionStore.loadOrCreate(file);

        assertTrue(store.snapshot().isEmpty());
        assertTrue(Files.readString(file).contains("\"subscriptions\""));
    }

    @Test
    void migratesLegacyNewlineDelimitedSubscriptions() throws IOException {
        Files.writeString(dir.resolve("subscriptions.txt"), "a".repeat(64) + System.lineSeparator()
                + "b".repeat(64) + System.lineSeparator());

        Path jsonFile = dir.resolve("subscriptions.json");
        SubscriptionStore store = SubscriptionStore.loadOrCreate(jsonFile);

        assertEquals(Set.of("a".repeat(64), "b".repeat(64)), store.snapshot());
        assertTrue(Files.exists(jsonFile));
    }
}
