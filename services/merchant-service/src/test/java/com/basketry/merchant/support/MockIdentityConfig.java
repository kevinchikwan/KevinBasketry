package com.basketry.merchant.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Replaces the RestClient that IdentityClient uses with one bound to a
 * MockRestServiceServer, so tests script identity-service's merchant-link
 * answers (status, body, exceptions) and check each request. Token
 * verification is unaffected: the JWKS still comes from
 * {@link FakeIdentityServer#shared()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockIdentityConfig {

    public static final String BASE_URL = "http://identity.test";
    public static final String MERCHANT_LINK_URL = BASE_URL + "/internal/accounts/merchant-link";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    // Binding before build() makes every client built from this builder use the mock server.
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Bean
    MockRestServiceServer identityMockServer() {
        return server;
    }

    @Bean
    @Primary
    RestClient mockIdentityRestClient() {
        return builder.build();
    }
}
