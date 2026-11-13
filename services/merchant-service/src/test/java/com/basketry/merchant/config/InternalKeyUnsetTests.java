package com.basketry.merchant.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.identityclient.InternalApiKey;
import com.basketry.merchant.support.FakeIdentityServer;
import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.MockIdentityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no key configured (local dev), the service starts and refuses every
 * internal call, whatever is sent. The same context as
 * OnboardingWithoutApiKeyTests. Mutation: an unset key matching an empty
 * header (comparing against the digest of "").
 */
@SpringBootTest(properties = "basketry.internal.api-key=")
@AutoConfigureMockMvc
@Import(MockIdentityConfig.class)
class InternalKeyUnsetTests {

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyInternalCallIs401() throws Exception {
        mockMvc.perform(get("/internal/merchants").param("ids", "1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/merchants").param("ids", "1").header(InternalApiKey.HEADER, ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/merchants").param("ids", "1")
                        .header(InternalApiKey.HEADER, MerchantServiceTestBase.API_KEY))
                .andExpect(status().isUnauthorized());
    }
}
