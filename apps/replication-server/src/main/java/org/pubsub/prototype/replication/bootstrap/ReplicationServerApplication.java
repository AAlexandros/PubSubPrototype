package org.pubsub.prototype.replication.bootstrap;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.auth.PublisherProgressAuthenticator;
import org.pubsub.prototype.auth.SignedHttpRequest;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.adapter.CardanoCliEpochProvider;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EpochProvider;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.RegistryObserver;
import org.pubsub.prototype.registry.RegistryDiagnostics;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationRegistryReader;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistry;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryConfig;
import org.pubsub.prototype.replication.adapter.http.ReplicationHttpAdapter;
import org.pubsub.prototype.replication.config.ReplicationServerConfig;
import org.pubsub.prototype.replication.health.PeerFailureDetector;
import org.pubsub.prototype.replication.service.ReplicaMaintenanceService;
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


/** Owns construction and lifecycle for one replication-server process. */
public final class ReplicationServerApplication implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationServerApplication.class);

    private final ReplicationServerConfig config;
    private final RegistryObserver<List<ReplicationServer>> members;
    private final RegistryObserver<List<TopicState>> topics;
    private final FileEventStore store;
    private final ReplicationHttpClient client;
    private final ReplicaMaintenanceService replicaMaintenance;
    private final ReplicationHttpAdapter httpAdapter;
    private final ScheduledExecutorService maintenance;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ReplicationServerApplication(ReplicationServerConfig config,
                                         RegistryObserver<List<ReplicationServer>> members,
                                         RegistryObserver<List<TopicState>> topics,
                                         FileEventStore store, ReplicationHttpClient client,
                                         ReplicaMaintenanceService replicaMaintenance,
                                         ReplicationHttpAdapter httpAdapter,
                                         ScheduledExecutorService maintenance) {
        this.config = config;
        this.members = members;
        this.topics = topics;
        this.store = store;
        this.client = client;
        this.replicaMaintenance = replicaMaintenance;
        this.httpAdapter = httpAdapter;
        this.maintenance = maintenance;
    }

    public static ReplicationServerApplication create(ReplicationServerConfig config) throws IOException {
        // Initialize the two registries: replication and topic
        // Registry used to track active replication servers
        ReplicationRegistryReader replicationRegistry = new CardanoReplicationRegistry(
                new CardanoReplicationRegistryConfig(config.replicationRegistryRuntimeDir(),
                        config.topicRegistrySigner(), config.topicRegistryCliBackend()));
        RegistryObserver<List<ReplicationServer>> members = new RegistryObserver<>(
                "replication", config.serverId(), replicationRegistry::activeServers,
                config.membershipPollMs(), config.registryMaximumAgeMs());
        ReplicationMembership membership = members::requireFresh;
        // Topic registry instance that is used to validate events, acquiring replication factors etc.
        CardanoTopicRegistry topicRegistry = new CardanoTopicRegistry(new CardanoRegistryConfig(
                config.topicRegistryRuntimeDir(), config.topicRegistrySigner(), config.topicRegistryCliBackend()));
        RegistryObserver<List<TopicState>> topicObserver = new RegistryObserver<>(
                "topic", config.serverId(), topicRegistry::allTopicsSnapshot,
                config.membershipPollMs(), config.registryMaximumAgeMs());

        EpochProvider epochs = new CardanoCliEpochProvider(CardanoCommandRunnerFactory.create(
                config.topicRegistryCliBackend(), config.topicRegistryRuntimeDir()));
        FileEventStore store = new FileEventStore(config.storagePath(), Clock.systemUTC(), epochs);
        store.ensureServerIdentity(config.serverId());

        // Shared client for outbound requests to peer replication servers.
        SignedHttpRequest requestSigner = SignedHttpRequest.load(config.requestSigningKeyPath(),
                config.requestVerificationKeyPath(), config.serverId());
        ReplicationHttpClient client = new ReplicationHttpClient(Duration.ofMillis(config.connectionTimeoutMs()),
                Duration.ofMillis(config.requestTimeoutMs()), config.retries(), requestSigner);
        TopicStateProvider topicProvider = id -> topicObserver.requireFresh().stream()
                .filter(topic -> topic.topicId().equals(id)).findFirst();
        PeerFailureDetector failureDetector = new PeerFailureDetector(config.failureProbeAttempts());
        PersistenceEventValidator eventAuthenticator = new PersistenceEventValidator(topicProvider);
        PublisherProgressAuthenticator progressAuthenticator = new PublisherProgressAuthenticator(topicProvider);
        ReplicationService service = new ReplicationService(config.self(), membership,
                eventAuthenticator, topicProvider, store, client, failureDetector);
        ReplicaMaintenanceService replicaMaintenance = new ReplicaMaintenanceService(config.self(), membership,
                service, store, client, () -> topicObserver.requireFresh().stream()
                        .filter(TopicState::active).toList(), failureDetector,
                Duration.ofMillis(config.failureProbeTimeoutMs()));
        ReplicationHttpAdapter httpAdapter = new ReplicationHttpAdapter(config.listenHost(), config.port(),
                config.self(), service, membership, replicaMaintenance, eventAuthenticator,
                progressAuthenticator, config.httpWorkerThreads(),
                () -> RegistryDiagnostics.snapshot(topicObserver.freshness(), topicObserver.ageMs(),
                        topicObserver.lastObserved().orElse(List.of()),
                        members.freshness(), members.ageMs(),
                        members.lastObserved().orElse(List.of())));
        ScheduledExecutorService maintenance = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "replication-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        return new ReplicationServerApplication(config, members, topicObserver, store, client,
                replicaMaintenance, httpAdapter, maintenance);
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
        members.start();
        topics.start();
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                store.cleanupExpired();
            } catch (RuntimeException ex) {
                LOG.warn("Expiration cleanup failed: {}", ex.getMessage());
            }
        }, config.cleanupIntervalMs(), config.cleanupIntervalMs(), TimeUnit.MILLISECONDS);
        httpAdapter.start();
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
            httpAdapter.close();
            maintenance.shutdownNow();
            members.close();
            topics.close();
            client.close();
        } finally {
            stopped.countDown();
        }
    }
}
