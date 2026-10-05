package org.pubsub.prototype.cardano.cli;

import java.util.Locale;

/** Supported sources of Cardano registry state and commands. */
public enum CardanoCliBackend {
    /** Read snapshots without live CLI access. */
    CACHE_ONLY,
    /** Run the CLI installed on the host. */
    HOST,
    /** Run the CLI inside the local Docker devnet. */
    DEVNET;

    /**
     * Parses a configuration value into a backend.
     *
     * @param value configured backend name
     * @return parsed backend
     */
    public static CardanoCliBackend parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cardano CLI backend must be specified");
        }
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported Cardano CLI backend: " + value, ex);
        }
    }
}
