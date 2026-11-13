package com.basketry.order.config;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key (DESIGN.md Section 3) order-service sends to
 * catalog-service's and merchant-service's /internal/**. It serves no
 * /internal/** of its own yet. The same rules as the other services: from
 * {@code basketry.internal.api-key} ({@code BASKETRY_INTERNAL_API_KEY}),
 * whitespace stripped, shorter than {@value #MIN_LENGTH} characters = startup
 * fails. Unset: the service starts, logs a WARN, and every checkout is refused
 * with 503 INTERNAL_API_KEY_NOT_CONFIGURED. The key is never logged.
 */
@Component
public class InternalApiKey {

    public static final String HEADER = "X-Internal-Api-Key";
    static final int MIN_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InternalApiKey.class);

    private final String key;

    public InternalApiKey(@Value("${basketry.internal.api-key:}") String configuredKey) {
        String stripped = configuredKey == null ? "" : configuredKey.strip();
        if (stripped.isEmpty()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "every checkout will be refused.");
            this.key = null;
        } else if (stripped.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.internal.api-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.key = stripped;
        }
    }

    /** The key for an outgoing call, or empty when none is configured. */
    public Optional<String> value() {
        return Optional.ofNullable(key);
    }

    @Override
    public String toString() {
        return "InternalApiKey[" + (key == null ? "unset" : "***") + "]";
    }
}
