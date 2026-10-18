package org.pubsub.prototype.auth;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;

import static org.pubsub.prototype.util.validation.Validators.require;

/** Verifies the publisher signatures on an event and its progress entry. */
public final class PublisherEventAuthenticator implements Authenticator<EventEnvelope, String> {
    @Override
    public String authenticate(EventEnvelope event) {
        require(EventCrypto.verify(event), "invalid event signature");
        PublisherProgress.fromEvent(event);
        return EventCrypto.publisherKeyId(event);
    }
}
