package com.basketry.identity.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key that service-to-service callers (merchant-service)
 * present on /internal/** (DESIGN.md Section 3). Comes from
 * {@code basketry.internal.api-key}, which application.yml fills from the
 * {@code BASKETRY_INTERNAL_API_KEY} environment variable (a Kubernetes
 * Secret when deployed). No value is ever committed.
 *
 * <ul>
 *   <li>Unset: the service still starts, logs a warning, and every
 *       /internal/** request is refused.</li>
 *   <li>Set but shorter than {@value #MIN_LENGTH} characters: startup fails.</li>
 * </ul>
 */
@Component
public class InternalApiKey {

    public static final String HEADER = "X-Internal-Api-Key";
    static final int MIN_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InternalApiKey.class);

    /** SHA-256 of the configured key, or null when no key is configured. */
    private final byte[] expectedDigest;

    public InternalApiKey(@Value("${basketry.internal.api-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "every /internal/** request will be refused.");
            this.expectedDigest = null;
        } else if (configuredKey.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.internal.api-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.expectedDigest = sha256(configuredKey);
        }
    }

    /**
     * Constant-time check: both sides are hashed to the same length first, so
     * neither the comparison time nor the key length leaks.
     */
    public boolean matches(String presentedKey) {
        if (expectedDigest == null || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presentedKey));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
