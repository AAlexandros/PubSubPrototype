package org.pubsub.prototype.navigation;

import org.pubsub.prototype.util.JsonFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.pubsub.prototype.util.NavigationConstants.JSON_EXTENSION;
import static org.pubsub.prototype.util.NavigationConstants.LEGACY_TEXT_EXTENSION;
import static org.pubsub.prototype.util.NavigationConstants.SUBSCRIPTIONS_FIELD;

/** Persistent JSON set of subscribed topic ids. Subscriptions are local node state. */
public final class SubscriptionStore {
    private record StoredSubscriptions(List<String> subscriptions) {
        private StoredSubscriptions {
            subscriptions = List.copyOf(Objects.requireNonNull(subscriptions, SUBSCRIPTIONS_FIELD));
        }
    }

    private final Path file;
    private final Set<String> subscriptions = Collections.synchronizedSet(new LinkedHashSet<>());

    private SubscriptionStore(Path file, Set<String> initial) {
        this.file = file;
        subscriptions.addAll(initial);
    }

    public static SubscriptionStore loadOrCreate(Path path) {
        try {
            if (Files.exists(path)) {
                StoredSubscriptions stored = JsonFiles.read(path, StoredSubscriptions.class);
                return new SubscriptionStore(path, new LinkedHashSet<>(stored.subscriptions()));
            }

            Path legacyPath = legacyPath(path);
            if (legacyPath != null && Files.exists(legacyPath)) {
                SubscriptionStore migrated = new SubscriptionStore(path, readLegacy(legacyPath));
                migrated.persist();
                return migrated;
            }

            SubscriptionStore created = new SubscriptionStore(path, Set.of());
            created.persist();
            return created;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load or create subscription store at " + path, ex);
        }
    }

    public synchronized boolean add(String topicId) {
        boolean changed = subscriptions.add(topicId);
        if (changed) {
            persist();
        }
        return changed;
    }

    public synchronized boolean remove(String topicId) {
        boolean changed = subscriptions.remove(topicId);
        if (changed) {
            persist();
        }
        return changed;
    }

    public synchronized Set<String> snapshot() {
        return Set.copyOf(subscriptions);
    }

    private void persist() {
        try {
            JsonFiles.writeAtomic(file, new StoredSubscriptions(List.copyOf(subscriptions)));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist subscription store at " + file, ex);
        }
    }

    private static Path legacyPath(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(JSON_EXTENSION)) {
            return null;
        }
        return path.resolveSibling(fileName.substring(0, fileName.length() - JSON_EXTENSION.length())
                + LEGACY_TEXT_EXTENSION);
    }

    private static Set<String> readLegacy(Path path) throws IOException {
        Set<String> loaded = new LinkedHashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                loaded.add(line.trim());
            }
        }
        return loaded;
    }
}
