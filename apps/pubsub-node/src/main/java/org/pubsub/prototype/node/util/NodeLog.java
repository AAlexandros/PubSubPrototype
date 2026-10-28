package org.pubsub.prototype.node.util;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/** Routes component events to focused files while retaining the component logger name. */
public final class NodeLog {
    private static final Marker INIT = MarkerFactory.getMarker("INITIALIZATION");
    private static final Marker NETWORK = MarkerFactory.getMarker("NETWORK");

    private NodeLog() {
    }

    public static void init(Logger logger, String message, Object... arguments) {
        logger.info(INIT, message, arguments);
    }

    public static void network(Logger logger, String message, Object... arguments) {
        logger.info(NETWORK, message, arguments);
    }

    /** Compact peer labels in network messages; the node's own file identifies the local node. */
    public static String node(String nodeId) {
        if (nodeId == null) return "none";
        return shortId(nodeId);
    }

    /** Short labels keep topic identifiers readable in high-volume logs. */
    public static String topic(String topicId) {
        return shortId(topicId);
    }

    private static String shortId(String value) {
        if (value == null || value.length() <= 4) return String.valueOf(value);
        return value.substring(0, 4);
    }
}
