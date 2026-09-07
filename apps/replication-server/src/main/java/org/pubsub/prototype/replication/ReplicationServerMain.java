package org.pubsub.prototype.replication;

import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.core.FileEventStore;
import org.pubsub.prototype.persistence.core.FileReplicationRegistry;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.pubsub.prototype.persistence.core.SystemEpochProvider;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ReplicationServerMain {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationServerMain.class);

    private ReplicationServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: replication-server <config.yaml>");
            System.exit(2);
        }
        ReplicationServerConfig config = ReplicationServerConfig.load(Path.of(args[0]));
        FileReplicationRegistry registry = new FileReplicationRegistry(config.membershipPath());
        AtomicReference<List<ReplicationServer>> members = new AtomicReference<>(registry.activeServers());
        LOG.info("REPLICATION_REGISTRY_SYNCED serverId={} activeServerCount={} membership={}",
                config.serverId(), members.get().size(), members.get());
        ReplicationMembership membership = members::get;
        CardanoTopicRegistry topics = new CardanoTopicRegistry(new CardanoRegistryConfig(
                config.topicRegistryRuntimeDir(), config.topicRegistrySigner()));
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
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                List<ReplicationServer> observed = registry.activeServers();
                if (!observed.equals(members.getAndSet(observed))) {
                    LOG.info("REPLICATION_REGISTRY_SYNCED serverId={} activeServerCount={} membership={}",
                            config.serverId(), observed.size(), observed);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Replication registry sync failed: {}", ex.getMessage());
            }
        }, 0, config.membershipPollMs(), TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(() -> {
            try { store.cleanupExpired(); } catch (RuntimeException ex) { LOG.warn("Expiration cleanup failed: {}", ex.getMessage()); }
        }, config.cleanupIntervalMs(), config.cleanupIntervalMs(), TimeUnit.MILLISECONDS);
        server.start();
        maintenance.scheduleWithFixedDelay(() -> {
            try { replicaMaintenance.runOnce(); }
            catch (RuntimeException ex) { LOG.warn("Replica maintenance failed: {}", ex.getMessage()); }
        }, 0, config.maintenanceIntervalMs(), TimeUnit.MILLISECONDS);
        LOG.info("REPLICATION_SERVER_STARTED serverId={} host={} port={} storagePath={}",
                config.serverId(), config.advertisedHost(), config.port(), config.storagePath());
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            maintenance.shutdownNow();
            client.close();
            stop.countDown();
        }, "replication-shutdown"));
        stop.await();
    }
}
