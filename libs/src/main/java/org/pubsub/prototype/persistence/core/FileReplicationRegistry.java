package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.persistence.ReplicationRegistry;
import org.pubsub.prototype.persistence.ReplicationServerState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Durable reconstruction/cache of the replication-registry UTxO set. */
public final class FileReplicationRegistry implements ReplicationRegistry {
    private static final TypeReference<List<ReplicationServerState>> TYPE = new TypeReference<>() { };
    private final Path file;

    public FileReplicationRegistry(Path file) {
        this.file = file;
    }

    public static String serverId(byte[] encodedCardanoVerificationKey) {
        return EventCrypto.sha256Hex(encodedCardanoVerificationKey);
    }

    @Override
    public synchronized ReplicationServerState registerServer(ReplicationServerState server, String controllingOperator) {
        authorize(server.operator(), controllingOperator);
        List<ReplicationServerState> states = new ArrayList<>(read());
        states.stream().filter(existing -> existing.serverId().equals(server.serverId()))
                .findFirst().ifPresent(existing -> authorize(existing.operator(), controllingOperator));
        states.removeIf(existing -> existing.serverId().equals(server.serverId()));
        states.add(server);
        write(states);
        return server;
    }

    @Override
    public synchronized void unregisterServer(String serverId, String controllingOperator) {
        List<ReplicationServerState> states = new ArrayList<>(read());
        ReplicationServerState current = states.stream().filter(value -> value.serverId().equals(serverId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown serverId: " + serverId));
        authorize(current.operator(), controllingOperator);
        states.remove(current);
        states.add(current.deactivate());
        write(states);
    }

    @Override
    public synchronized List<ReplicationServerState> queryServers(boolean includeInactive) {
        return read().stream().filter(server -> includeInactive || server.active())
                .sorted(Comparator.comparing(ReplicationServerState::serverId)).toList();
    }

    private List<ReplicationServerState> read() {
        if (!Files.exists(file)) return List.of();
        try {
            return PersistenceJson.MAPPER.readValue(file.toFile(), TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to reconstruct replication registry from " + file, ex);
        }
    }

    private void write(List<ReplicationServerState> states) {
        try {
            AtomicFiles.write(file, PersistenceJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(states));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist replication registry " + file, ex);
        }
    }

    private static void authorize(String expected, String actual) {
        if (!expected.equals(actual)) throw new SecurityException("controlling Cardano identity signature is required");
    }
}
