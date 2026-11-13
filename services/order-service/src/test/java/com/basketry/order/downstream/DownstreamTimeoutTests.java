package com.basketry.order.downstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.support.FakeServices;
import com.basketry.order.support.OrderServiceTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A slow service is a 503 once its read timeout passes, with no retry (DESIGN.md
 * Section 3, order-service). The timeout is shortened here so the test is quick.
 */
@TestPropertySource(properties = {
        "basketry.identity.read-timeout=300ms",
        "basketry.catalog.read-timeout=300ms",
        "basketry.merchant.read-timeout=300ms"})
class DownstreamTimeoutTests extends OrderServiceTestBase {

    @Autowired
    private CatalogClient catalog;

    @Autowired
    private IdentityClient identity;

    @Autowired
    private MerchantClient merchantClient;

    /**
     * Each call gives up after its read timeout and is tried once. Mutations: the
     * read timeout not set on the request factory (the call waits for the 2 s
     * answer); a retry (two calls).
     */
    @Test
    void aSlowServiceIsUnavailableAfterTheReadTimeout() {
        services.answerAfter(FakeServices.PRODUCTS, Duration.ofSeconds(2));
        services.answerAfter(FakeServices.ACCOUNTS, Duration.ofSeconds(2));
        services.answerAfter(FakeServices.MERCHANTS, Duration.ofSeconds(2));

        Instant start = Instant.now();
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> catalog.products(List.of(10L))).code()).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> identity.account(12, "t")).code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> merchantClient.merchants(List.of(3L))).code()).isEqualTo("MERCHANT_UNAVAILABLE");

        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(1800));
        assertThat(services.calls()).hasSize(3);
    }
}
