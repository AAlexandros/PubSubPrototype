package org.pubsub.prototype.replication.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

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
                  requestSigningKeyPath: /keys/payment.skey
                  requestVerificationKeyPath: /keys/payment.vkey
                registration:
                  operator: replication-server-1
                  cliBackend: DEVNET
                  commitmentStartEpoch: 0
                  commitmentEndEpoch: 100000
                registry:
                  replicationRuntimeDir: /replication-registry
                  pollIntervalMs: 1000
                  maximumAgeMs: 10000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                  topicRegistryCliBackend: HOST
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                  maintenanceIntervalMs: 1000
                """);

        assertEquals("replication-server-1", config.advertisedHost());
        assertEquals(1000, config.membershipPollMs());
        assertEquals(3000, config.requestTimeoutMs());
        assertEquals(3, config.failureProbeAttempts());
        assertEquals("replication-server-1", config.registration().operator());
        assertEquals(100_000, config.registration().commitmentEndEpoch());
        assertEquals(CardanoCliBackend.HOST, config.topicRegistryCliBackend());
        assertEquals(CardanoCliBackend.DEVNET, config.registration().cliBackend());
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
                  requestSigningKeyPath: /keys/payment.skey
                  requestVerificationKeyPath: /keys/payment.vkey
                  unexpected: true
                registration:
                  operator: replication-server-1
                  cliBackend: DEVNET
                  commitmentStartEpoch: 0
                  commitmentEndEpoch: 100000
                registry:
                  replicationRuntimeDir: /replication-registry
                  pollIntervalMs: 1000
                  maximumAgeMs: 10000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                  topicRegistryCliBackend: HOST
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                  maintenanceIntervalMs: 1000
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
                  requestSigningKeyPath: /keys/payment.skey
                  requestVerificationKeyPath: /keys/payment.vkey
                registration:
                  operator: replication-server-1
                  cliBackend: DEVNET
                  commitmentStartEpoch: 0
                  commitmentEndEpoch: 100000
                registry:
                  replicationRuntimeDir: /replication-registry
                  pollIntervalMs: 1000
                  maximumAgeMs: 10000
                  topicRegistryRuntimeDir: /registry
                  topicRegistrySigner: replication-server-1
                  topicRegistryCliBackend: HOST
                timing:
                  requestTimeoutMs: 3000
                  connectionTimeoutMs: 1000
                  retries: 1
                  cleanupIntervalMs: 5000
                  failureProbeAttempts: 3
                  failureProbeTimeoutMs: 5000
                """));

        assertEquals("timing.maintenanceIntervalMs is required", exception.getMessage());
    }

    private ReplicationServerConfig load(String yaml) throws IOException {
        Path path = tempDir.resolve("replication-server.yaml");
        Files.writeString(path, yaml);
        return ReplicationServerConfig.load(path);
    }
}
