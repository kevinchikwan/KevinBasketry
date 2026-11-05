package com.basketry.merchant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where identity-service is and how long to wait for it (DESIGN.md Section 3,
 * merchant-service). The same base URL serves the JWKS endpoint (token
 * verification) and the internal merchant-link endpoint (onboarding).
 *
 * @param baseUrl        from {@code BASKETRY_IDENTITY_URL}, e.g. http://localhost:8080
 * @param connectTimeout to open a connection, for both the JWKS fetch and merchant-link
 * @param readTimeout    to wait for a response, for both the JWKS fetch and merchant-link
 * @param retryDelay     pause before the one automatic retry of a failed merchant-link call
 */
@ConfigurationProperties("basketry.identity")
public record IdentityServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration retryDelay) {

    public String jwksUri() {
        return baseUrl + "/.well-known/jwks.json";
    }
}
