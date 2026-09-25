package org.pubsub.prototype.node.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** Creates single-thread executors backed by named daemon threads. */
public final class AsyncUtil {
    private AsyncUtil() {
    }

    public static ExecutorService singleThread(String threadName) {
        return Executors.newSingleThreadExecutor(threadFactory(threadName));
    }

    public static ScheduledExecutorService scheduledSingleThread(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(threadFactory(threadName));
    }

    private static ThreadFactory threadFactory(String threadName) {
        return runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
    }
}
