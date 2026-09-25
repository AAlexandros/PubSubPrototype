package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.RegistrySnapshot;
import org.pubsub.prototype.registry.TopicState;

public final class TopicDatumCodec {
    private final JsonCodec json = new JsonCodec();

    public String encode(TopicState topic) {
        return json.pretty(topic, "topic datum");
    }

    public TopicState decode(String json) {
        return this.json.read(json, TopicState.class, "topic datum");
    }

    String encodeStore(RegistryStore store) {
        return json.pretty(store, "registry store");
    }

    RegistryStore decodeStore(String json) {
        return this.json.read(json, RegistryStore.class, "registry store");
    }

    public String encodeSnapshot(RegistrySnapshot snapshot) {
        return json.pretty(snapshot, "registry snapshot");
    }
}
