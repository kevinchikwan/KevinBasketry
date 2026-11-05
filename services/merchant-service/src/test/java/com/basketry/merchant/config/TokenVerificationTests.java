package com.basketry.merchant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.FakeIdentityServer;
import com.basketry.merchant.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bearer tokens are verified against identity-service's JWKS, served here by
 * a real local HTTP server whose keys the tests change, as an identity-service
 * restart does. Each test publishes its own fresh key, so the decoder's cache
 * from an earlier test never decides the outcome.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenVerificationTests {

    private static final FakeIdentityServer IDENTITY = FakeIdentityServer.start(TestTokens.newKey());

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

    private RSAKey key;

    @BeforeEach
    void publishFreshKey() {
        IDENTITY.failJwks(false);
        key = TestTokens.newKey();
        IDENTITY.publish(key);
    }

    private ResultActions call(String token) throws Exception {
        return mockMvc.perform(get("/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    void tokenSignedWithAPublishedKeyIsAccepted() throws Exception {
        call(TestTokens.admin(key)).andExpect(status().isOk());
    }

    @Test
    void afterIdentityRestartsWithANewKeyNewTokensWorkAndOldOnesAre401() throws Exception {
        call(TestTokens.admin(key)).andExpect(status().isOk()); // old key now cached

        RSAKey afterRestart = TestTokens.newKey();
        IDENTITY.publish(afterRestart);
        int fetchesBefore = IDENTITY.jwksRequests();

        // Unknown kid: the decoder fetches the JWKS again and finds the new key.
        call(TestTokens.admin(afterRestart)).andExpect(status().isOk());
        assertThat(IDENTITY.jwksRequests()).isGreaterThan(fetchesBefore);

        // The old key is no longer published.
        call(TestTokens.admin(key)).andExpect(status().isUnauthorized());
    }

    @Test
    void keyThatIdentityDoesNotPublishIs401() throws Exception {
        call(TestTokens.admin(TestTokens.newKey())).andExpect(status().isUnauthorized());
    }

    @Test
    void jwksFailingWithAnUnknownKeyIs503NotA401OrA500() throws Exception {
        IDENTITY.failJwks(true);

        call(TestTokens.admin(key))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Token verification is temporarily unavailable. Try again shortly."));
    }

    @Test
    void jwksFailingDoesNotMatterForAKeyAlreadyCached() throws Exception {
        call(TestTokens.admin(key)).andExpect(status().isOk());
        IDENTITY.failJwks(true);

        call(TestTokens.admin(key)).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        IDENTITY.publish(TestTokens.KEY);
        call(TestTokens.expiredAdmin()).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedSignatureIs401() throws Exception {
        String token = TestTokens.admin(key);
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        call(tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIs401() throws Exception {
        call("not-a-jwt").andExpect(status().isUnauthorized());
    }
}
