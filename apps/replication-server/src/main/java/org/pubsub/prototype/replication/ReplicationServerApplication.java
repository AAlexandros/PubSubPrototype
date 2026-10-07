package org.pubsub.prototype.replication;

import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.ReplicationRegistryReader;
import org.pubsub.prototype.persistence.core.FileEventStore;
import org.pubsub.prototype.persistence.core.FileReplicationRegistry;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.pubsub.prototype.persistence.core.SystemEpochProvider;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoTopicRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns construction and lifecycle for one replication-server process. */
public final class ReplicationServerApplication implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationServerApplication.class);

    private final ReplicationServerConfig config;
    private final ReplicationRegistryReader registry;
    private final AtomicReference<List<ReplicationServer>> members;
    private final FileEventStore store;
    private final ReplicationHttpClient client;
    private final ReplicaMaintenanceManager replicaMaintenance;
    private final ReplicationHttpServer server;
    private final ScheduledExecutorService maintenance;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ReplicationServerApplication(ReplicationServerConfig config, ReplicationRegistryReader registry,
                                         AtomicReference<List<ReplicationServer>> members,
                                         FileEventStore store, ReplicationHttpClient client,
                                         ReplicaMaintenanceManager replicaMaintenance,
                                         ReplicationHttpServer server,
                                         ScheduledExecutorService maintenance) {
        this.config = config;
        this.registry = registry;
        this.members = members;
        this.store = store;
        this.client = client;
        this.replicaMaintenance = replicaMaintenance;
        this.server = server;
        this.maintenance = maintenance;
    }

    public static ReplicationServerApplication create(ReplicationServerConfig config) throws IOException {
        // Get the locally stored replication server states
        // This happens once at creation of the application, then the membership is updated periodically by the polling mechanism.
        ReplicationRegistryReader registry = new FileReplicationRegistry(config.membershipPath());
        // Polling replaces this complete membership snapshot while request and maintenance threads read it
        AtomicReference<List<ReplicationServer>> members = new AtomicReference<>(List.copyOf(registry.activeServers()));
        LOG.info("REPLICATION_REGISTRY_SYNCED serverId={} activeServerCount={} membership={}",
                config.serverId(), members.get().size(), members.get());
        ReplicationMembership membership = members::get;
        CardanoTopicRegistry topics = new CardanoTopicRegistry(new CardanoRegistryConfig(
                config.topicRegistryRuntimeDir(), config.topicRegistrySigner(), config.topicRegistryCliBackend()));
        FileEventStore store = new FileEventStore(config.storagePath(), Clock.systemUTC(),
                new SystemEpochProvider(Clock.systemUTC(), config.epochZeroTimeMs(), config.epochLengthMs()));
        store.ensureServerIdentity(config.serverId());
        ReplicationHttpClient client = new ReplicationHttpClient(Duration.ofMillis(config.connectionTimeoutMs()),
                Duration.ofMillis(config.requestTimeoutMs()), config.retries());
        ReplicationService service = new ReplicationService(config.self(), membership,
                new PersistenceEventValidator(topicId -> topics.topic(topicId)), store, client);
        ReplicaMaintenanceManager replicaMaintenance = new ReplicaMaintenanceManager(config.self(), membership,
                service, store, client, () -> topics.snapshot().topics(), config.failureProbeAttempts(),
                Duration.ofMillis(config.failureProbeTimeoutMs()));
        ReplicationHttpServer server = new ReplicationHttpServer(config.listenHost(), config.port(),
                config.self(), service, membership, replicaMaintenance);
        ScheduledExecutorService maintenance = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "replication-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        return new ReplicationServerApplication(config, registry, members, store, client,
                replicaMaintenance, server, maintenance);
    }

    public void runUntilShutdown() throws InterruptedException {
        Thread shutdownHook = new Thread(this::close, "replication-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            start();
            stopped.await();
        } finally {
            close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down and is running the hook.
            }
        }
    }

    public void start() {
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                List<ReplicationServer> observed = List.copyOf(registry.activeServers());
                if (!observed.equals(members.getAndSet(observed))) {
                    LOG.info("REPLICATION_REGISTRY_SYNCED serverId={} activeServerCount={} membership={}",
                            config.serverId(), observed.size(), observed);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Replication registry sync failed: {}", ex.getMessage());
            }
        }, 0, config.membershipPollMs(), TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                store.cleanupExpired();
            } catch (RuntimeException ex) {
                LOG.warn("Expiration cleanup failed: {}", ex.getMessage());
            }
        }, config.cleanupIntervalMs(), config.cleanupIntervalMs(), TimeUnit.MILLISECONDS);
        server.start();
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                replicaMaintenance.runOnce();
            } catch (RuntimeException ex) {
                LOG.warn("Replica maintenance failed: {}", ex.getMessage());
            }
        }, 0, config.maintenanceIntervalMs(), TimeUnit.MILLISECONDS);
        LOG.info("REPLICATION_SERVER_STARTED serverId={} host={} port={} storagePath={}",
                config.serverId(), config.advertisedHost(), config.port(), config.storagePath());
    }

    @Override
    public void close() {
        try {
            server.close();
            maintenance.shutdownNow();
            client.close();
        } finally {
            stopped.countDown();
        }
    }
}
