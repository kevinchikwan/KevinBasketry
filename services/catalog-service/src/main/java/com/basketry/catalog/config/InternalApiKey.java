package com.basketry.catalog.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key (DESIGN.md Section 3), used both ways: catalog-service
 * sends it to inventory-service's /internal/stock, and requires it on its own
 * /internal/**. The same rules as identity-service and
 * inventory-service: from {@code basketry.internal.api-key}
 * ({@code BASKETRY_INTERNAL_API_KEY}), whitespace stripped, shorter than
 * {@value #MIN_LENGTH} characters = startup fails.
 *
 * <p>Unset: the service starts and logs a WARN. Calls to inventory-service go
 * out without the header, so it answers 401: the stock-row relay keeps retrying
 * (and logs an error), and listings show availability UNKNOWN. The key is never
 * logged.
 */
@Component
public class InternalApiKey {

    public static final String HEADER = "X-Internal-Api-Key";
    static final int MIN_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InternalApiKey.class);

    /** The key to send, or null when none is configured. */
    private final String key;
    /** SHA-256 of the key, or null when none is configured. */
    private final byte[] expectedDigest;

    public InternalApiKey(@Value("${basketry.internal.api-key:}") String configuredKey) {
        // A Secret mounted or created from a file often carries a trailing newline.
        String stripped = configuredKey == null ? "" : configuredKey.strip();
        if (stripped.isEmpty()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "every /internal/** request will be refused, and inventory-service will refuse the "
                    + "stock-row relay and the stock reads (401).");
            this.key = null;
            this.expectedDigest = null;
        } else if (stripped.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.internal.api-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.key = stripped;
            this.expectedDigest = sha256(stripped);
        }
    }

    /** The key for an outgoing call, or empty when none is configured. */
    public Optional<String> value() {
        return Optional.ofNullable(key);
    }

    /** Constant time: both sides are hashed to the same length first. */
    public boolean matches(String presentedKey) {
        if (expectedDigest == null || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presentedKey));
    }

    @Override
    public String toString() {
        return "InternalApiKey[" + (key == null ? "unset" : "***") + "]";
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
