package com.basketry.order.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.order.support.TestTokens;
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
 * identity-service is not running: a token cannot be verified, so it is 503,
 * not 401 (which would log the user out) and not 500. As in the other
 * services. Probes never need identity-service.
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
    private MockMvc mvc;

    /** Mutation: the failure handler not installed (a 401). */
    @Test
    void aWellFormedTokenIs503WhenIdentityIsDown() throws Exception {
        mvc.perform(get("/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.buyer(12)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        mvc.perform(get("/orders").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /** Mutation: the health endpoint not permitted (a 401 for the probes). */
    @Test
    void healthAnswersWithoutIdentity() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
