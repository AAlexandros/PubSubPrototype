package org.pubsub.prototype.auth;

import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import static org.pubsub.prototype.util.validation.Validators.require;

/** Checks that signed publisher progress belongs to an active topic and an allowed publisher. */
public final class PublisherProgressAuthenticator
        implements Authenticator<PublisherProgressAuthenticator.Request, TopicState> {
    private final TopicStateProvider topics;

    public PublisherProgressAuthenticator(TopicStateProvider topics) {
        this.topics = topics;
    }

    @Override
    public TopicState authenticate(Request request) {
        PublisherProgress progress = request.progress();
        require(request.topicId().equals(progress.topicId()), "publisher progress topic does not match request");
        TopicState topic = topics.topic(new TopicId(request.topicId()))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        require(topic.publishers().isEmpty() || topic.publishers().contains(progress.publisherKeyId()),
                "unauthorized publisher");
        return topic;
    }

    public record Request(String topicId, PublisherProgress progress) { }
}
