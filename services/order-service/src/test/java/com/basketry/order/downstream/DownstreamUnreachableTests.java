package com.basketry.order.downstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A service nobody listens for is a 503 with its own code, not an exception
 * escaping as a 500. Mutation: RestClientException not caught in HttpAnswer.
 */
@SpringBootTest
class DownstreamUnreachableTests {

    @DynamicPropertySource
    static void nobodyListens(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + closedPort();
        registry.add("basketry.identity.base-url", () -> url);
        registry.add("basketry.catalog.base-url", () -> url);
        registry.add("basketry.merchant.base-url", () -> url);
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private IdentityClient identity;

    @Autowired
    private CatalogClient catalog;

    @Autowired
    private MerchantClient merchantClient;

    @Test
    void eachRefusedConnectionIsItsServicesCode() {
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> identity.account(12, "t")).code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> catalog.products(List.of(10L))).code()).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(catchThrowableOfType(DownstreamUnavailableException.class,
                () -> merchantClient.merchants(List.of(3L))).code()).isEqualTo("MERCHANT_UNAVAILABLE");
    }
}
