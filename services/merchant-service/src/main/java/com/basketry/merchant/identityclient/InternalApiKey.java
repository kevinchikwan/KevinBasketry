package com.basketry.merchant.identityclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared API key this service sends to identity-service's /internal/**
 * endpoints (DESIGN.md Section 3, merchant-link). Comes from
 * {@code basketry.internal.api-key}, which application.yml fills from the
 * {@code BASKETRY_INTERNAL_API_KEY} environment variable, the same variable
 * identity-service reads. No value is ever committed.
 *
 * <ul>
 *   <li>Unset: the service still starts and logs a warning, but onboarding
 *       is refused with 503 before anything is saved.</li>
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

    private final String key;

    public InternalApiKey(@Value("${basketry.internal.api-key:}") String configuredKey) {
        String stripped = configuredKey == null ? "" : configuredKey.strip();
        if (stripped.isEmpty()) {
            log.warn("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                    + "merchant onboarding will be refused.");
            this.key = null;
        } else if (stripped.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.internal.api-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.key = stripped;
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

    @Override
    public String toString() {
        return "InternalApiKey[" + (key == null ? "unset" : "***") + "]";
    }
}
