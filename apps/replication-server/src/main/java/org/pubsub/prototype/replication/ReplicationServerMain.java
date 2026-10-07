package org.pubsub.prototype.replication;

import java.nio.file.Path;

/** Command-line entry point for a replication server. */
public final class ReplicationServerMain {
    private ReplicationServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: replication-server <config.yaml>");
            System.exit(2);
        }

        ReplicationServerConfig config = ReplicationServerConfig.load(Path.of(args[0]));
        ReplicationServerApplication.create(config).runUntilShutdown();
    }
}
