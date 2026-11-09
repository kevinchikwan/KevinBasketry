package com.basketry.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where identity-service is and how long to wait for it: only for its JWKS,
 * to verify bearer tokens (DESIGN.md Section 3, inventory-service).
 *
 * @param baseUrl        from {@code BASKETRY_IDENTITY_URL}, e.g. http://localhost:8080
 * @param connectTimeout to open a connection for the JWKS fetch
 * @param readTimeout    to wait for the JWKS response
 */
@ConfigurationProperties("basketry.identity")
public record IdentityServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public String jwksUri() {
        return baseUrl + "/.well-known/jwks.json";
    }
}
