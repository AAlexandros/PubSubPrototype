package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.persistence.ReplicationRegistryReader;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.util.JsonSupport;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Reads replication-server states from the local JSON snapshot materialized from Cardano registry UTxOs.
 *
 * <p>The file contains a JSON array with one object per decoded registration, using the
 * {@link ReplicationServerState} component names. For example:
 *
 * <pre>{@code
 * [
 *   {
 *     "serverId": "<64-character lowercase hex>",
 *     "operator": "<Cardano payment-key hash>",
 *     "host": "replication-server-1",
 *     "port": 8100,
 *     "commitmentStartEpoch": 0,
 *     "commitmentEndEpoch": 100000,
 *     "active": true
 *   }
 * ]
 * }</pre>
 *
 * <p>This is decoded registry state, not raw UTxO JSON; UTxO references and output values are not stored.
 * Each read checks the file again, so a later call may observe a newer materialized registry state.
 */
public final class FileReplicationRegistry implements ReplicationRegistryReader {
    // Type reference used to correctly parse the JSON file as a list of ReplicationServerState.
    private static final TypeReference<List<ReplicationServerState>> TYPE = new TypeReference<>() { };
    private final Path file;

    public FileReplicationRegistry(Path file) {
        this.file = file;
    }

    /** Returns the lowercase hexadecimal SHA-256 ID derived from encoded Cardano verification-key bytes. */
    public static String serverId(byte[] encodedCardanoVerificationKey) {
        return EventCrypto.sha256Hex(encodedCardanoVerificationKey);
    }

    /** Reads all server states from the snapshot; an absent file represents an empty registry. */
    private List<ReplicationServerState> read() {
        if (!Files.exists(file)) return List.of();
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonSupport.MAPPER.readValue(reader, TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read replication-registry snapshot from " + file, ex);
        }
    }

    /**
     * Returns snapshot states sorted by server ID.
     *
     * @param includeInactive whether to include registrations marked inactive
     * @see org.pubsub.prototype.persistence.ReplicationRegistryReader#queryServers(boolean)
     */
    @Override
    public List<ReplicationServerState> queryServers(boolean includeInactive) {
        return read().stream().filter(server -> includeInactive || server.active())
                .sorted(Comparator.comparing(ReplicationServerState::serverId)).toList();
    }
}
