package com.basketry.promotion.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key that order-service presents on /internal/** (DESIGN.md
 * Section 3). A copy of inventory-service's, the same rules as identity-service:
 * from {@code basketry.internal.api-key} ({@code BASKETRY_INTERNAL_API_KEY}),
 * whitespace stripped, unset = a warning and every /internal/** request
 * refused (401), shorter than {@value #MIN_LENGTH} characters = startup fails.
 * The key is never logged.
 */
@Component
public class InternalApiKey {

    public static final String HEADER = "X-Internal-Api-Key";
    static final int MIN_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InternalApiKey.class);

    /** SHA-256 of the configured key, or null when no key is configured. */
    private final byte[] expectedDigest;

    public InternalApiKey(@Value("${basketry.internal.api-key:}") String configuredKey) {
        // A Secret mounted or created from a file often carries a trailing newline.
        String key = configuredKey == null ? "" : configuredKey.strip();
        if (key.isEmpty()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "every /internal/** request will be refused.");
            this.expectedDigest = null;
        } else if (key.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.internal.api-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.expectedDigest = sha256(key);
        }
    }

    /** Constant time: both sides are hashed to the same length first. Never true when no key is configured. */
    public boolean matches(String presentedKey) {
        if (expectedDigest == null || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presentedKey));
    }

    @Override
    public String toString() {
        return "InternalApiKey[" + (expectedDigest == null ? "unset" : "***") + "]";
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
