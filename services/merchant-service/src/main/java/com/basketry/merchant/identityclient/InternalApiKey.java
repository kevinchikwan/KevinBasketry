package com.basketry.merchant.identityclient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key (DESIGN.md Section 3), used both ways: merchant-service
 * sends it to identity-service's /internal/** (merchant-link), and requires it
 * on its own /internal/** (order-service's GET /internal/merchants). Comes from
 * {@code basketry.internal.api-key}, which application.yml fills from the
 * {@code BASKETRY_INTERNAL_API_KEY} environment variable, the same variable
 * identity-service reads. No value is ever committed.
 *
 * <ul>
 *   <li>Unset: the service still starts and logs a warning; onboarding is
 *       refused with 503 before anything is saved, and every /internal/**
 *       request is refused (401).</li>
 *   <li>Set but shorter than {@value #MIN_LENGTH} characters: startup fails,
 *       the same rule identity-service applies.</li>
 * </ul>
 *
 * <p>Leading and trailing whitespace (such as a Secret's trailing newline) is
 * stripped first. The key is never logged.
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
        String stripped = configuredKey == null ? "" : configuredKey.strip();
        if (stripped.isEmpty()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "merchant onboarding will be refused, and every /internal/** request will be refused.");
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

    public boolean isConfigured() {
        return key != null;
    }

    String value() {
        if (key == null) {
            throw new IllegalStateException("basketry.internal.api-key is not set.");
        }
        return key;
    }

    /**
     * Whether an incoming /internal/** request presented the key. Never true
     * when no key is configured. Both sides are hashed to the same length
     * first, so the comparison takes the same time wherever they differ.
     */
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
