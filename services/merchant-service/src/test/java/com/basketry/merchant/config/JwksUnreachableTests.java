package com.basketry.merchant.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.TestTokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * identity-service is not running at all (connection refused): nothing can
 * be verified, so every bearer request is 503, not 401 and not a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
    private MockMvc mockMvc;

    @Test
    void anyTokenIs503WhenIdentityIsDown() throws Exception {
        mockMvc.perform(get("/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void malformedTokenIsStill401BecauseNoKeyIsNeededToRejectIt() throws Exception {
        mockMvc.perform(get("/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noTokenIsStill401() throws Exception {
        mockMvc.perform(get("/merchants")).andExpect(status().isUnauthorized());
    }
}
