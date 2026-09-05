package org.pubsub.prototype.securecyclon;

import java.util.List;

/** Samples carry evidence only; they never grant ownership or enter the view. */
public record Exchange(List<SecureLink> links, List<SecureLink> samples, List<LinkProof> reports) {
    public Exchange(List<SecureLink> links, List<SecureLink> samples) { this(links, samples, List.of()); }
    public Exchange { reports = List.copyOf(reports); links = List.copyOf(links); samples = List.copyOf(samples); }
}
