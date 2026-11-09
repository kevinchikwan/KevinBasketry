package com.basketry.inventory.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.TestStockCounterConfig;
import com.basketry.inventory.support.TestTokens;
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
 * As in merchant-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestStockCounterConfig.class)
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
        mvc.perform(get("/stock").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void aMalformedTokenIsStill401() throws Exception {
        mvc.perform(get("/stock").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /** Probes never need identity-service. */
    @Test
    void healthStillAnswersWithoutIdentity() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
