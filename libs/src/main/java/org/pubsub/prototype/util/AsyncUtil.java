package org.pubsub.prototype.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pubsub.prototype.util.validation.Validators.requireNonBlank;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;

/** Creates named daemon executors shared by the applications. */
public final class AsyncUtil {
    private AsyncUtil() {
    }

    /** Creates one worker for tasks that should run sequentially. */
    public static ExecutorService singleThread(String threadName) {
        return Executors.newSingleThreadExecutor(threadFactory(threadName, 1));
    }

    /** Creates one worker for periodic tasks that should run sequentially. */
    public static ScheduledExecutorService scheduledSingleThread(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(threadFactory(threadName, 1));
    }

    /**
     * Creates a fixed-size scheduled pool so independent periodic tasks can run concurrently.
     * Workers use numbered names when the pool has more than one thread.
     */
    public static ScheduledExecutorService scheduledThreadPool(String threadName, int threadCount) {
        requirePositive(threadCount, "threadCount");
        return Executors.newScheduledThreadPool(threadCount, threadFactory(threadName, threadCount));
    }

    private static ThreadFactory threadFactory(String threadName, int threadCount) {
        String name = requireNonBlank(threadName, "threadName");
        AtomicInteger nextThreadNumber = new AtomicInteger();
        return runnable -> {
            String workerName = threadCount == 1 ? name : name + "-" + nextThreadNumber.incrementAndGet();
            Thread thread = new Thread(runnable, workerName);
            thread.setDaemon(true);
            return thread;
        };
    }
}
