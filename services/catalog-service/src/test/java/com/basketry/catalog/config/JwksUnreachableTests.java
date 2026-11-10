package com.basketry.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.TestCacheConfig;
import com.basketry.catalog.support.TestTokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * identity-service is not running (connection refused): a token cannot be
 * verified, so it is 503, not 401 (which would log the user out) and not 500.
 * As in merchant-service. The public reads never need identity-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "basketry.inventory.base-url=http://127.0.0.1:1")
class JwksUnreachableTests {

    @DynamicPropertySource
    static void nothingListening(DynamicPropertyRegistry registry) {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("basketry.identity.base-url", () -> "http://127.0.0.1:" + port);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void aWellFormedTokenIs503WhenIdentityIsDown() throws Exception {
        mvc.perform(get("/manage/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void aMalformedTokenIsStill401() throws Exception {
        mvc.perform(get("/manage/products").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Browsing never needs identity-service, even with a token the Buyer app
     * sends. Mutation: put the resource server in the public chain (a 503).
     */
    @Test
    void thePublicReadsAnswerWithoutIdentity() throws Exception {
        mvc.perform(get("/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()))
                .andExpect(status().isOk());
    }

    /** Probes never need identity-service. */
    @Test
    void healthStillAnswersWithoutIdentity() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
