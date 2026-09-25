package org.pubsub.prototype.node;

import org.pubsub.prototype.node.bootstrap.NodeApplication;
import org.pubsub.prototype.node.config.NodeConfig;

import java.nio.file.Path;

public final class PubSubNodeMain {
    private PubSubNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: pubsub-node <config.yaml>");
            System.exit(2);
        }

        NodeConfig config = NodeConfig.load(Path.of(args[0]));
        NodeApplication.create(config).runUntilShutdown();
    }
}
