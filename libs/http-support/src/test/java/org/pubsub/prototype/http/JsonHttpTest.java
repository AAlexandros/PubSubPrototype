package org.pubsub.prototype.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonHttpTest {
    @Test
    void extractsOnlyChildrenOfTheRequestedRoute() {
        assertEquals("", JsonHttp.suffix(URI.create(ApiPaths.EVENTS), ApiPaths.EVENTS));
        assertEquals("abc", JsonHttp.suffix(URI.create(ApiPaths.EVENTS + "/abc"), ApiPaths.EVENTS));
        assertEquals("", JsonHttp.suffix(URI.create(ApiPaths.EVENTS + "-archive/abc"), ApiPaths.EVENTS));
    }

    @Test
    void parsesFlagAndValueQueryParameters() {
        var query = JsonHttp.query(URI.create("/route?forceBroadcast&tamperSignature=false"));
        assertEquals("true", query.get("forceBroadcast"));
        assertEquals("false", query.get("tamperSignature"));
    }

    @Test
    void readsNonNegativeLongWithFallback() {
        assertEquals(5, JsonHttp.queryLong(URI.create("/route?sinceTimestamp=5"), "sinceTimestamp", 0));
        assertEquals(5, JsonHttp.queryLong(
                URI.create("/route?sinceTimestamp=5&sinceTimestamp=9"), "sinceTimestamp", 0));
        assertEquals(7, JsonHttp.queryLong(URI.create("/route"), "sinceTimestamp", 7));
        assertThrows(IllegalArgumentException.class,
                () -> JsonHttp.queryLong(URI.create("/route?sinceTimestamp=-1"), "sinceTimestamp", 0));
    }
}
