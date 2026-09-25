package org.pubsub.prototype.http;

import org.pubsub.prototype.util.SharedFieldNames;

/** JSON field names shared by the node and replication HTTP APIs. */
public final class ApiFields {
    public static final String NODE_ID = SharedFieldNames.NODE_ID;
    public static final String TOPIC_ID = SharedFieldNames.TOPIC_ID;
    public static final String EVENT_KEY = SharedFieldNames.EVENT_KEY;
    public static final String PUBLISHER_KEY_ID = SharedFieldNames.PUBLISHER_KEY_ID;
    public static final String SERVER_ID = SharedFieldNames.SERVER_ID;

    public static final String CAPACITY = "capacity";
    public static final String VIEW = "view";
    public static final String VIEWS = "views";
    public static final String TOPIC_INDEX = "topicIndex";
    public static final String SUBSCRIPTIONS = "subscriptions";
    public static final String FINGER_TOPICS = "fingerTopics";
    public static final String SUBSCRIBED = "subscribed";
    public static final String PREDECESSOR = "predecessor";
    public static final String SUCCESSOR = "successor";
    public static final String RANDOM_PEERS = "randomPeers";
    public static final String STATUS = "status";
    public static final String MEMBERSHIP = "membership";
    public static final String EVENT_KEYS = "eventKeys";

    private ApiFields() {
    }
}
