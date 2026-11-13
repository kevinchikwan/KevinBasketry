package com.basketry.order.config;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key of the Idempotency-Key fingerprint (DESIGN.md Section 2,
 * order-service): {@code basketry.order.fingerprint-key}
 * ({@code BASKETRY_ORDER_FINGERPRINT_KEY}). The internal key's rules:
 * whitespace stripped, shorter than {@value #MIN_LENGTH} characters = startup
 * fails, unset = a WARN at startup and every checkout refused with 503
 * FINGERPRINT_KEY_NOT_CONFIGURED. The key is never logged.
 *
 * <p>Keyed, because the fingerprint covers the card number, and a plain hash
 * of a card number could be reversed by trying every number.
 */
@Component
public class FingerprintKey {

    static final int MIN_LENGTH = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private static final Logger log = LoggerFactory.getLogger(FingerprintKey.class);

    private final SecretKeySpec key;

    public FingerprintKey(@Value("${basketry.order.fingerprint-key:}") String configuredKey) {
        String stripped = configuredKey == null ? "" : configuredKey.strip();
        if (stripped.isEmpty()) {
            log.warn("basketry.order.fingerprint-key is not set (env BASKETRY_ORDER_FINGERPRINT_KEY); "
                    + "every checkout will be refused.");
            this.key = null;
        } else if (stripped.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "basketry.order.fingerprint-key must be at least " + MIN_LENGTH + " characters.");
        } else {
            this.key = new SecretKeySpec(stripped.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        }
    }

    /** HMAC-SHA256 of {@code text}, as 64 lowercase hex digits; empty when no key is configured. */
    public Optional<String> hmac(String text) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is always available on the JVM", e);
        }
    }

    @Override
    public String toString() {
        return "FingerprintKey[" + (key == null ? "unset" : "***") + "]";
    }
}
