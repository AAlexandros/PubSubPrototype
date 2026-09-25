package org.pubsub.prototype.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationServerConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsTypedConfiguration() throws IOException {
        ReplicationServerConfig config = load("""
                server:
                  serverId: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                  listenHost: 0.0.0.0
                  advertisedHost: replication-server-1
                  port: 8100
                  storagePath: /data
                registry:
                  membershipPath: /registry/servers.json
                  pollIntervalMs: 1000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                  maintenanceIntervalMs: 1000
                epoch:
                  zeroTimeMs: 0
                  lengthMs: 432000000
                """);

        assertEquals("replication-server-1", config.advertisedHost());
        assertEquals(1000, config.membershipPollMs());
        assertEquals(3000, config.requestTimeoutMs());
        assertEquals(432_000_000, config.epochLengthMs());
        assertEquals(3, config.failureProbeAttempts());
    }

    @Test
    void rejectsUnknownProperties() throws IOException {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> load("""
                server:
                  serverId: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                  listenHost: 0.0.0.0
                  advertisedHost: replication-server-1
                  port: 8100
                  storagePath: /data
                  unexpected: true
                registry:
                  membershipPath: /registry/servers.json
                  pollIntervalMs: 1000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                  maintenanceIntervalMs: 1000
                epoch:
                  zeroTimeMs: 0
                  lengthMs: 432000000
                """));

        assertTrue(exception.getCause().getMessage().contains("unexpected"));
    }

    @Test
    void rejectsMissingValuesInsteadOfApplyingJavaDefaults() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> load("""
                server:
                  serverId: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                  listenHost: 0.0.0.0
                  advertisedHost: replication-server-1
                  port: 8100
                  storagePath: /data
                registry:
                  membershipPath: /registry/servers.json
                  pollIntervalMs: 1000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                epoch:
                  zeroTimeMs: 0
                  lengthMs: 432000000
                """));

        assertEquals("timing.maintenanceIntervalMs is required", exception.getMessage());
    }

    private ReplicationServerConfig load(String yaml) throws IOException {
        Path path = tempDir.resolve("replication-server.yaml");
        Files.writeString(path, yaml);
        return ReplicationServerConfig.load(path);
    }
}
