package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.FakeIdentityServer;
import com.basketry.merchant.support.MockIdentityConfig;
import com.basketry.merchant.support.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no internal API key configured, the service starts, but onboarding and
 * the link retry are refused with 503 before anything is saved or sent.
 */
@SpringBootTest(properties = "basketry.internal.api-key=")
@AutoConfigureMockMvc
@Import(MockIdentityConfig.class)
class OnboardingWithoutApiKeyTests {

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private MockRestServiceServer identity;

    @BeforeEach
    void clear() {
        identity.reset();
        merchants.deleteAll();
    }

    /** No expectations were set, so any merchant-link call fails this. */
    @AfterEach
    void noIdentityCall() {
        identity.verify();
    }

    @Test
    void onboardingIs503AndNothingIsSaved() throws Exception {
        mockMvc.perform(post("/merchants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Shop","category":"Food","kycVerified":true,"commissionRate":5,
                                 "loginEmail":"shop@example.com","initialPassword":"Init1al-Pass!"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTERNAL_API_KEY_NOT_CONFIGURED"));

        assertThat(merchants.count()).isZero();
    }

    @Test
    void linkRetryIs503AndTheMerchantStaysPending() throws Exception {
        Merchant pending = merchants.save(new Merchant(merchants.nextId(), "Shop", "Food", true,
                new BigDecimal("5.00"), "shop@example.com", Instant.now()));

        mockMvc.perform(post("/merchants/{code}/account-link", pending.getMerchantCode())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTERNAL_API_KEY_NOT_CONFIGURED"));

        assertThat(merchants.findById(pending.getId()).orElseThrow().getAccountLinkStatus())
                .isEqualTo(AccountLinkStatus.PENDING);
    }
}
