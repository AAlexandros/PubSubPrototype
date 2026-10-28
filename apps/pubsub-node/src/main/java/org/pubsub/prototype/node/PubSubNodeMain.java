package org.pubsub.prototype.node;

import org.pubsub.prototype.node.bootstrap.PubSubNodeApplication;
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
        if (System.getProperty("NODE_LOG_FILE") == null && System.getenv("NODE_LOG_FILE") == null) {
            System.setProperty("NODE_LOG_FILE", config.identityPath().resolve("node.log").toString());
        }
        if (System.getProperty("NODE_INIT_LOG_FILE") == null && System.getenv("NODE_INIT_LOG_FILE") == null) {
            System.setProperty("NODE_INIT_LOG_FILE", config.identityPath().resolve("initialization.log").toString());
        }
        if (System.getProperty("NODE_NETWORK_LOG_FILE") == null && System.getenv("NODE_NETWORK_LOG_FILE") == null) {
            System.setProperty("NODE_NETWORK_LOG_FILE", config.identityPath().resolve("network.log").toString());
        }
        PubSubNodeApplication.create(config).runUntilShutdown();
    }
}
