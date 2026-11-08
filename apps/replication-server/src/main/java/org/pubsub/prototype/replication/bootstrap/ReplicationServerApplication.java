package org.pubsub.prototype.replication.bootstrap;

import org.pubsub.prototype.cardano.cli.CardanoCommandRunnerFactory;
import org.pubsub.prototype.auth.PublisherProgressAuthenticator;
import org.pubsub.prototype.auth.ReplicationHttpRequestSignatures;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.adapter.CardanoCliEpochProvider;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EpochProvider;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.RegistryObserver;
import org.pubsub.prototype.registry.RegistryDiagnostics;
import org.pubsub.prototype.registry.replication.ReplicationMembershipProvider;
import org.pubsub.prototype.registry.replication.ReplicationRegistryReader;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.topic.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.TopicStateSnapshotProvider;
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

/** Owns construction and lifecycle for one replication-server process. */
public final class ReplicationServerApplication implements AutoCloseable {

        // Default logger
        private static final Logger LOG = LoggerFactory.getLogger(ReplicationServerApplication.class);

        // Registry observers
        private final RegistryObserver<List<ReplicationServerEndpoint>> members;
        private final RegistryObserver<List<TopicState>> topics;

        private final ReplicaMaintenanceService replicaMaintenance;

        private final ReplicationHttpClient replicationHttpClient;
        private final CountDownLatch stopped = new CountDownLatch(1);

        // Creates an HTTP API for sending commands to this node and inspecting its
        // state. The testbed exposes this port on the host's loopback interface.
        private final ReplicationHttpAdapter httpAdapter;

        private ReplicationServerApplication(RegistryObserver<List<ReplicationServerEndpoint>> members,
                        RegistryObserver<List<TopicState>> topics,
                        ReplicationHttpClient replicationHttpClient,
                        ReplicaMaintenanceService replicaMaintenance,
                        ReplicationHttpAdapter httpAdapter) {
                this.members = members;
                this.topics = topics;
                this.replicationHttpClient = replicationHttpClient;
                this.replicaMaintenance = replicaMaintenance;
                this.httpAdapter = httpAdapter;
        }

        public static ReplicationServerApplication create(ReplicationServerConfig config) throws IOException {
                // Initialize the two registries: replication and topic
                // Registry used to track active replication servers
                ReplicationRegistryReader replicationRegistry = new CardanoReplicationRegistry(
                                new CardanoReplicationRegistryConfig(config.replicationRegistryRuntimeDir(),
                                                config.topicRegistrySigner(), config.topicRegistryCliBackend()));
                RegistryObserver<List<ReplicationServerEndpoint>> members = new RegistryObserver<>(
                                "replication", config.serverId(), replicationRegistry::activeServers,
                                config.membershipPollMs(), config.registryMaximumAgeMs());
                ReplicationMembershipProvider membership = members::requireFresh;
                // Topic registry instance that is used to validate events, acquiring
                // replication factors etc.
                CardanoTopicRegistry topicRegistry = new CardanoTopicRegistry(new CardanoRegistryConfig(
                                config.topicRegistryRuntimeDir(), config.topicRegistrySigner(),
                                config.topicRegistryCliBackend()));
                RegistryObserver<List<TopicState>> topicObserver = new RegistryObserver<>(
                                "topic", config.serverId(), topicRegistry::allTopicsSnapshot,
                                config.membershipPollMs(), config.registryMaximumAgeMs());
                TopicStateProvider topicProvider = id -> topicObserver.requireFresh().stream()
                                .filter(topic -> topic.topicId().equals(id)).findFirst();

                EpochProvider epochs = new CardanoCliEpochProvider(CardanoCommandRunnerFactory.create(
                                config.topicRegistryCliBackend(), config.topicRegistryRuntimeDir()));
                FileEventStore store = new FileEventStore(config.storagePath(), Clock.systemUTC(), epochs, topicProvider);
                store.ensureServerIdentity(config.serverId());

                // Shared client for outbound requests to peer replication servers.
                ReplicationHttpRequestSignatures.RequestSigner requestSigner =
                                ReplicationHttpRequestSignatures.loadSignerFromCardanoKeys(
                                                config.requestSigningKeyPath(),
                                                config.requestVerificationKeyPath(), config.serverId());
                ReplicationHttpClient client = new ReplicationHttpClient(
                                Duration.ofMillis(config.connectionTimeoutMs()),
                                Duration.ofMillis(config.requestTimeoutMs()), config.retries(), requestSigner);
                PeerFailureDetector failureDetector = new PeerFailureDetector(config.failureProbeAttempts());
                PersistenceEventValidator eventAuthenticator = new PersistenceEventValidator(topicProvider);
                PublisherProgressAuthenticator progressAuthenticator = new PublisherProgressAuthenticator(
                                topicProvider);

                ReplicationService service = new ReplicationService(config.self(), membership,
                                eventAuthenticator, topicProvider, store, client, failureDetector);
                TopicStateSnapshotProvider activeTopicSnapshot = () -> topicObserver.requireFresh().stream()
                                .filter(TopicState::active).toList();
                ReplicaMaintenanceService replicaMaintenance = new ReplicaMaintenanceService(config.self(), membership,
                                service, store, client, activeTopicSnapshot,
                                failureDetector, Duration.ofMillis(config.failureProbeTimeoutMs()),
                                config.cleanupIntervalMs(), config.maintenanceIntervalMs());
                ReplicationHttpAdapter httpAdapter = new ReplicationHttpAdapter(config.listenHost(), config.port(),
                                config.self(), service, membership, replicaMaintenance, eventAuthenticator,
                                progressAuthenticator, config.httpWorkerThreads(),
                                () -> RegistryDiagnostics.snapshot(topicObserver.freshness(), topicObserver.ageMs(),
                                                topicObserver.lastObserved().orElse(List.of()),
                                                members.freshness(), members.ageMs(),
                                                members.lastObserved().orElse(List.of())));

                ReplicationServerApplication application = new ReplicationServerApplication(members, topicObserver,
                                client,
                                replicaMaintenance, httpAdapter);

                LOG.info("REPLICATION_SERVER_CONFIGURED serverId={} host={} port={} storagePath={}",
                                config.serverId(), config.advertisedHost(), config.port(), config.storagePath());
                return application;
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
                httpAdapter.start();
                replicaMaintenance.start();
                LOG.info("REPLICATION_SERVER_STARTED");
        }

        @Override
        public void close() {
                try {
                        httpAdapter.close();
                        replicaMaintenance.close();
                        members.close();
                        topics.close();
                        replicationHttpClient.close();
                } finally {
                        stopped.countDown();
                }
        }
}
