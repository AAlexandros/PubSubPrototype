package org.pubsub.prototype.util;

/** Stable event names emitted by the SecureCyclon protocol and its runtime adapters. */
public enum SecureCyclonEvent {
    CYCLE,
    GOSSIP_SENT,
    GOSSIP_RECEIVED,
    EXCHANGE_REJECTED,
    PEER_REMOVED,
    PEER_DISCOVERED,
    VIEW_UPDATED;

    @Override
    public String toString() {
        return "SECURECYCLON_" + name();
    }
}
