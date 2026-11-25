package com.basketry.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.account.BuyerStatusRequest;
import com.basketry.identity.account.RegisterAccountRequest;
import com.basketry.identity.auth.LoginRequest;
import com.basketry.identity.merchantlink.MerchantLinkRequest;
import com.basketry.identity.web.RequestBodyReader;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every endpoint with a body reads it through RequestBodyReader, never through
 * Spring (ROADMAP F2). For login this is the only check that can fail: its
 * fields are strings, which Jackson never fails on, and its toString is
 * masked, so Spring reading it would leak nothing HttpJacksonMessageLeakTests
 * could see today, but would the moment a field or the mask changed.
 */
@SpringBootTest(properties = "basketry.internal.api-key=" + BodyReadingTests.KEY)
@AutoConfigureMockMvc
class BodyReadingTests {

    static final String KEY = "test-only-identity-reading-key-not-a-secret";

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private RequestBodyReader bodies;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registration;

    private long targetId;

    @BeforeEach
    void anAccount() {
        accounts.deleteAll();
        targetId = registration.registerBuyer("target@example.com", "Corr3ct-Horse!").getId();
    }

    /** Mutation: {@code @Valid @RequestBody RegisterAccountRequest} back in AccountController. */
    @Test
    void registerReadsItsBodyThroughTheReader() throws Exception {
        mvc.perform(post("/accounts/register").contentType("application/json")
                .content("{\"email\":\"a@example.com\",\"password\":\"Corr3ct-Horse!\"}"));

        verify(bodies).read(any(), any(), eq(RegisterAccountRequest.class));
    }

    /** Mutation: {@code @Valid @RequestBody LoginRequest} back in AuthController. */
    @Test
    void loginReadsItsBodyThroughTheReader() throws Exception {
        mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"email\":\"target@example.com\",\"password\":\"Corr3ct-Horse!\"}"));

        verify(bodies).read(any(), any(), eq(LoginRequest.class));
    }

    /** Mutation: {@code @Valid @RequestBody BuyerStatusRequest} back in AccountController. */
    @Test
    void buyerStatusReadsItsBodyThroughTheReader() throws Exception {
        String login = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"admin-placeholder@basketry.example\",\"password\":\"ChangeMe-Before-Deploy!\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(login, "$.accessToken");

        mvc.perform(patch("/accounts/{id}/buyer-status", targetId).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"buyerActive\":false}"));

        verify(bodies).read(any(), any(), eq(BuyerStatusRequest.class));
    }

    /** Mutation: {@code @Valid @RequestBody MerchantLinkRequest} back in MerchantLinkController. */
    @Test
    void merchantLinkReadsItsBodyThroughTheReader() throws Exception {
        mvc.perform(post("/internal/accounts/merchant-link").header("X-Internal-Api-Key", KEY)
                .contentType("application/json")
                .content("{\"email\":\"m@example.com\",\"merchantId\":5,\"initialPassword\":\"Corr3ct-Horse!\"}"));

        verify(bodies).read(any(), any(), eq(MerchantLinkRequest.class));
    }
}
