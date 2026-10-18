package org.pubsub.prototype.replication.bootstrap;

import org.pubsub.prototype.cardano.cli.HostCardanoCommandRunner;
import org.pubsub.prototype.auth.PublisherProgressAuthenticator;
import org.pubsub.prototype.auth.SignedHttpRequest;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.adapter.CardanoCliEpochProvider;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EpochProvider;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.replication.FileReplicationRegistry;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationRegistryReader;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.replication.adapter.http.ReplicationHttpServer;
import org.pubsub.prototype.replication.config.ReplicationServerConfig;
import org.pubsub.prototype.replication.service.PeerFailureDetector;
import org.pubsub.prototype.replication.service.ReplicaMaintenanceManager;
import org.pubsub.prototype.replication.service.ReplicationService;
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

        // Creates its own topic registry instance that is used to validate events, acquiring replication factors etc.
        CardanoTopicRegistry topics = new CardanoTopicRegistry(new CardanoRegistryConfig(
                config.topicRegistryRuntimeDir(), config.topicRegistrySigner(), config.topicRegistryCliBackend()));

        // This prototype approach of acquiring epoch information, is using the Cardano CLI to acquire the epoch tip 
        EpochProvider epochs = new CardanoCliEpochProvider(new HostCardanoCommandRunner(
                config.cardanoNetwork()));
        // Get the current epoch (approximated via tip)
        epochs.currentEpoch();

        FileEventStore store = new FileEventStore(config.storagePath(), Clock.systemUTC(), epochs);
        store.ensureServerIdentity(config.serverId());

        // Shared client for outbound requests to peer replication servers.
        SignedHttpRequest requestSigner = SignedHttpRequest.load(config.requestSigningKeyPath(),
                config.requestVerificationKeyPath(), config.serverId());
        ReplicationHttpClient client = new ReplicationHttpClient(Duration.ofMillis(config.connectionTimeoutMs()),
                Duration.ofMillis(config.requestTimeoutMs()), config.retries(), requestSigner);
        TopicStateProvider topicProvider = topicId -> topics.topic(topicId);
        PeerFailureDetector failureDetector = new PeerFailureDetector(config.failureProbeAttempts());
        PersistenceEventValidator eventAuthenticator = new PersistenceEventValidator(topicProvider);
        PublisherProgressAuthenticator progressAuthenticator = new PublisherProgressAuthenticator(topicProvider);
        ReplicationService service = new ReplicationService(config.self(), membership,
                eventAuthenticator, topicProvider, store, client, failureDetector);
        ReplicaMaintenanceManager replicaMaintenance = new ReplicaMaintenanceManager(config.self(), membership,
                service, store, client, () -> topics.snapshot().topics(), failureDetector,
                Duration.ofMillis(config.failureProbeTimeoutMs()));
        ReplicationHttpServer server = new ReplicationHttpServer(config.listenHost(), config.port(),
                config.self(), service, membership, replicaMaintenance, eventAuthenticator,
                progressAuthenticator, config.httpWorkerThreads());
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
        // The thread will run until a shutdown signal comes from the operating system.
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
