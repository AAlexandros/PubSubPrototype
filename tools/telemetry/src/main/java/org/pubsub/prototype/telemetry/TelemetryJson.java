package org.pubsub.prototype.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;

final class TelemetryJson {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private TelemetryJson() {
    }
}
