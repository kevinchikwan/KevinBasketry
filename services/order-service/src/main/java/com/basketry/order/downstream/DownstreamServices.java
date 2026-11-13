package com.basketry.order.downstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three services checkout calls synchronously (DESIGN.md Section 2,
 * order-service, checkout step 5): {@code basketry.identity},
 * {@code basketry.catalog} and {@code basketry.merchant} in application.yml.
 */
@ConfigurationProperties("basketry")
public record DownstreamServices(Endpoint identity, Endpoint catalog, Endpoint merchant) {

    /**
     * @param baseUrl        from BASKETRY_IDENTITY_URL, BASKETRY_CATALOG_URL or BASKETRY_MERCHANT_URL
     * @param connectTimeout to open a connection (2 s)
     * @param readTimeout    to wait for the answer (5 s); there is no retry inside a checkout
     */
    public record Endpoint(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }
}
