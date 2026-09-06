package org.pubsub.prototype.navigation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent, newline-delimited set of subscribed topic ids. Subscriptions are local node state. */
public final class SubscriptionStore {
    private final Path file;
    private final Set<String> subscriptions = Collections.synchronizedSet(new LinkedHashSet<>());

    private SubscriptionStore(Path file, Set<String> initial) {
        this.file = file;
        subscriptions.addAll(initial);
    }

    public static SubscriptionStore loadOrCreate(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (!Files.exists(path)) {
                Files.writeString(path, "");
                return new SubscriptionStore(path, Set.of());
            }
            Set<String> loaded = new LinkedHashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    loaded.add(line.trim());
                }
            }
            return new SubscriptionStore(path, loaded);
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

    public Set<String> snapshot() {
        return Set.copyOf(subscriptions);
    }

    private void persist() {
        try {
            Path tmp = Files.createTempFile(file.getParent(), "subscriptions", ".tmp");
            Files.write(tmp, List.copyOf(subscriptions), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist subscription store at " + file, ex);
        }
    }
}
