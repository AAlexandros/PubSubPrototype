package org.pubsub.prototype.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.pubsub.prototype.util.validation.Validators;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Maintains one application's local observation of a Cardano registry.
 * {@link #start()} schedules an immediate read, then periodic reads separated
 * by {@code pollIntervalMs} after each read completes. A successful read
 * atomically replaces the entire observation and records its completion time.
 * A failed read keeps the previous observation.
 *
 * <p>Freshness is the time since the last successful read. The application's
 * {@code registry.maximumAgeMs} setting limits how long that result may be used
 * for operations; {@link #requireFresh()} rejects a missing or older result.
 * {@link #lastObserved()} can still expose the older result for diagnostics.
 * {@link Freshness} reports whether no result has loaded, the result is fresh,
 * or it has become stale. The application also supplies
 * {@code registry.pollIntervalMs}, which controls how often the chain is read;
 * it is separate from the maximum age.</p>
 *
 * <p>For sensitive topics, the allowed stale period should be as small as one
 * Cardano slot, with polling frequent enough to observe changes on that scale.
 * Slot duration depends on the network. A one-slot age limit stops use of an
 * older observation; it cannot guarantee that a new chain state is observed
 * within one slot if a query takes longer or fails. These settings currently
 * apply to the whole registry view, not to individual topics.</p>
 *
 * <p>The {@code registry} label identifies the observed registry (for example,
 * topic or replication). The {@code instance} label identifies the running
 * Pub/Sub node or replication server. They identify logs and the polling
 * thread; the registry label also appears in unavailable errors. Neither
 * label changes the observation or freshness rules.</p>
 *
 * @param <T> complete registry snapshot returned by each successful read
 */
public final class RegistryObserver<T> implements AutoCloseable {

    /** Snapshot and time of one successful read, published together. */
    private record Observation<T>(T value, Instant observedAt) {
    }

    /** Whether an observation exists and is within the configured age limit. */
    public enum Freshness {
        /** No read has succeeded yet. */
        NOT_LOADED,
        /** The latest successful read is within the maximum age. */
        FRESH,
        /** The latest successful read exceeds the maximum age. */
        STALE
    }

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(RegistryObserver.class);

    // Names for validation errors
    private static final String REGISTRY_FIELD = "registry";
    private static final String INSTANCE_FIELD = "instance";
    private static final String READER_FIELD = "reader";
    private static final String POLL_INTERVAL_MS_FIELD = "pollIntervalMs";
    private static final String MAXIMUM_AGE_MS_FIELD = "maximumAgeMs";
    private static final String CLOCK_FIELD = "clock";
    private static final String READER_RESULT_FIELD = "registry reader result";

    // Runs blockchain polling on one thread
    private final ScheduledExecutorService executor;

    private final String registry;
    private final String instance;
    private final Supplier<T> reader;
    private final long pollIntervalMs;
    private final Duration maximumAge;
    private final Clock clock;
    /** Publishes the snapshot and its observation time together to other threads. */
    private volatile Observation<T> current;
    /** Prevents repeated stale warnings until a successful read restores freshness. */
    private volatile Freshness lastLoggedFreshness = Freshness.NOT_LOADED;

    /** Creates a view that uses the system UTC clock for observation times. */
    public RegistryObserver(String registry, String instance, Supplier<T> reader,
            long pollIntervalMs, long maximumAgeMs) {
        this(registry, instance, reader, pollIntervalMs, maximumAgeMs, Clock.systemUTC());
    }

    /**
     * Creates a view with an explicit clock.
     *
     * @param registry registry label used in logs, the polling thread name, and unavailable errors
     * @param instance application identity used in logs and the polling thread name
     * @param reader supplies one complete registry snapshot; a null result is treated as a failed read
     * @param pollIntervalMs delay between completed reads, in milliseconds
     * @param maximumAgeMs maximum permitted age of a successful read, in milliseconds
     * @param clock supplies observation times and the current time for freshness checks
     */
    public RegistryObserver(String registry, String instance, Supplier<T> reader,
            long pollIntervalMs, long maximumAgeMs, Clock clock) {
        // Validate constructor arguments
        this.registry = Validators.requireNonNull(registry, REGISTRY_FIELD);
        this.instance = Validators.requireNonNull(instance, INSTANCE_FIELD);
        this.reader = Validators.requireNonNull(reader, READER_FIELD);
        this.pollIntervalMs = Validators.requirePositive(pollIntervalMs, POLL_INTERVAL_MS_FIELD);
        this.maximumAge = Duration.ofMillis(Validators.requirePositive(maximumAgeMs, MAXIMUM_AGE_MS_FIELD));
        this.clock = Validators.requireNonNull(clock, CLOCK_FIELD);

        // Initialize the executor for polling the registry
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "registry-" + registry + "-" + instance);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts asynchronous polling with zero initial delay and a fixed delay
     * between reads. Call once per view.
     */
    public void start() {
        executor.scheduleWithFixedDelay(this::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Performs one read on the caller's thread. A successful result replaces
     * the whole observation; a failed or null result is logged and preserves
     * the previous observation. The scheduled task also calls this method.
     */
    public void poll() {
        long started = System.nanoTime();
        try {
            T next = Validators.requireNonNull(reader.get(), READER_RESULT_FIELD);
            current = new Observation<>(next, clock.instant());
            lastLoggedFreshness = Freshness.FRESH;
            LOG.info(
                    "REGISTRY_OBSERVED registry={} instance={} result=success ageMs=0 durationMs={} chainPosition=unavailable",
                    registry, instance, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (RuntimeException ex) {
            Freshness freshness = freshness();
            logStaleTransitionIfNeeded(freshness);
            String result = ex instanceof RegistryIdentityException ? "unexpected-identity"
                    : ex instanceof RegistryDataException ? "invalid-data" : "query-error";
            LOG.warn(
                    "REGISTRY_OBSERVED registry={} instance={} result={} freshness={} ageMs={} durationMs={} reason={}",
                    registry, instance, result, freshness, ageMs(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ex.toString());
        }
    }

    /**
     * Returns the last successful observation if it is within the maximum age.
     *
     * @throws RegistryUnavailableException if no read has succeeded or the result is stale
     */
    public T requireFresh() {
        Observation<T> observed = current;
        Freshness freshness = freshness(observed);
        if (freshness != Freshness.FRESH) {
            logStaleTransitionIfNeeded(freshness);
            throw new RegistryUnavailableException(registry + " registry is " + freshness);
        }
        return observed.value();
    }

    /** Reports whether an observation has loaded and is fresh or stale. */
    public Freshness freshness() {
        return freshness(current);
    }

    /**
     * Returns the last successful observation for diagnostics, even when stale.
     * Returns empty before the first successful read. Callers must use
     * {@link #requireFresh()} for operations that need current registry state.
     */
    public Optional<T> lastObserved() {
        Observation<T> observed = current;
        return observed == null ? Optional.empty() : Optional.of(observed.value());
    }

    /** Returns milliseconds since the last successful read, or {@code -1} before any success. */
    public long ageMs() {
        Observation<T> observed = current;
        return observed == null ? -1 : Math.max(0, Duration.between(observed.observedAt(), clock.instant()).toMillis());
    }

    /** Requests cancellation of scheduled polling and interruption of an active read. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Evaluates one observation against the maximum age at the current clock time. */
    private Freshness freshness(Observation<T> observed) {
        if (observed == null)
            return Freshness.NOT_LOADED;
        return clock.instant().isAfter(observed.observedAt().plus(maximumAge))
                ? Freshness.STALE
                : Freshness.FRESH;
    }

    /** Logs the first stale transition since the most recent successful read. */
    private void logStaleTransitionIfNeeded(Freshness freshness) {
        if (freshness == Freshness.STALE && lastLoggedFreshness != freshness) {
            synchronized (this) {
                if (lastLoggedFreshness != freshness) {
                    LOG.warn("REGISTRY_STALE registry={} instance={} ageMs={} maximumAgeMs={}",
                            registry, instance, ageMs(), maximumAge.toMillis());
                    lastLoggedFreshness = freshness;
                }
            }
        }
    }

}
