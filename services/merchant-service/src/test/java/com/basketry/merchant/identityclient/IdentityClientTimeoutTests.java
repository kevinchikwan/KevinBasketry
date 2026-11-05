package com.basketry.merchant.identityclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.MerchantRepository;
import com.basketry.merchant.support.FakeIdentityServer;
import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the read timeout is really configured on the production HTTP
 * client: a real local server that answers merchant-link far slower than the
 * timeout. No mocked RestClient here. The request must come back after about
 * two timeouts (the first attempt and the one retry), not after the server's
 * delay.
 */
@SpringBootTest(properties = {
        "basketry.internal.api-key=" + MerchantServiceTestBase.API_KEY,
        "basketry.identity.read-timeout=300ms",
        "basketry.identity.retry-delay=10ms"})
@AutoConfigureMockMvc
class IdentityClientTimeoutTests {

    private static final Duration SERVER_DELAY = Duration.ofSeconds(5);
    private static final FakeIdentityServer IDENTITY = FakeIdentityServer.start(TestTokens.KEY);

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", IDENTITY::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        IDENTITY.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchants;

    private final AtomicInteger linkCalls = new AtomicInteger();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void slowIdentity() {
        merchants.deleteAll();
        IDENTITY.onMerchantLink(exchange -> {
            linkCalls.incrementAndGet();
            apiKeys.add(exchange.getRequestHeaders().getFirst(InternalApiKey.HEADER));
            try {
                Thread.sleep(SERVER_DELAY);
                FakeIdentityServer.respond(exchange, 201, "application/json", "{\"outcome\":\"CREATED\"}");
            } catch (InterruptedException | java.io.IOException ignored) {
                // The client gave up long ago.
            }
        });
    }

    @Test
    void slowIdentityTimesOutTwiceAndLeavesTheMerchantPending() throws Exception {
        long start = System.nanoTime();

        mockMvc.perform(post("/merchants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Slow Shop","category":"Food","kycVerified":true,"commissionRate":5.00,
                                 "loginEmail":"slow@example.com","initialPassword":"Init1al-Pass!"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("IDENTITY_UNAVAILABLE"));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // Two attempts of 300 ms each: well under one server delay, and at least two timeouts.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(600)).isLessThan(Duration.ofSeconds(3));
        assertThat(linkCalls.get()).isEqualTo(2);
        assertThat(apiKeys).containsOnly(MerchantServiceTestBase.API_KEY);
    }
}
