package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * POST /merchants/{code}/account-link: Admin retries the link of a PENDING
 * merchant with the same merchantId. {@code email} defaults to the stored
 * loginEmail, which changes only when an attempt succeeds.
 */
class AccountLinkRetryTests extends MerchantServiceTestBase {

    private static final String STORED_EMAIL = "shop@example.com";

    private ResultActions retry(String token, String code, String body) throws Exception {
        MockHttpServletRequestBuilder request = bearer(post("/merchants/{code}/account-link", code), token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private Merchant reload(Merchant merchant) {
        return merchants.findById(merchant.getId()).orElseThrow();
    }

    @Test
    void retryWithNoBodyUsesTheStoredEmailAndSameMerchantId() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        identityLinks(HttpStatus.OK, "ALREADY_LINKED");

        retry(TestTokens.admin(), pending.getMerchantCode(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantCode").value(pending.getMerchantCode()))
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("ALREADY_LINKED"))
                .andExpect(jsonPath("$.accountLinkError").isEmpty())
                .andExpect(jsonPath("$.loginEmail").value(STORED_EMAIL));

        Map<String, Object> sent = sentLink(0);
        assertThat(sent.get("email")).isEqualTo(STORED_EMAIL);
        assertThat(((Number) sent.get("merchantId")).longValue()).isEqualTo(pending.getId());
        assertThat(sent).doesNotContainKey("initialPassword");
        assertThat(reload(pending).getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
    }

    @Test
    void retryWithAnEmptyObjectAlsoUsesTheStoredEmail() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        identityLinks(HttpStatus.OK, "ALREADY_LINKED");

        retry(TestTokens.admin(), pending.getMerchantCode(), "{}").andExpect(status().isOk());

        assertThat(sentLink(0).get("email")).isEqualTo(STORED_EMAIL);
    }

    @Test
    void onboardingThatTimedOutCanBeCompletedByARetry() throws Exception {
        expectLink(ExpectedCount.times(2)).andRespond(withServerError());
        identityLinks(HttpStatus.OK, "ALREADY_LINKED");
        String onboardBody = json(Map.of("name", "Shop", "category", "Food", "kycVerified", true,
                "commissionRate", new BigDecimal("5.00"), "loginEmail", STORED_EMAIL,
                "initialPassword", "Init1al-Pass!"));

        String created = mockMvc.perform(bearer(post("/merchants"), TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(onboardBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.merchantCode");

        retry(TestTokens.admin(), code, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("ALREADY_LINKED"));

        // All three calls (two by onboarding, one retry) were for the same merchantId.
        assertThat(linkRequests).hasSize(3);
        assertThat(sentLink(2).get("merchantId")).isEqualTo(sentLink(0).get("merchantId"));
        assertThat(merchants.count()).isEqualTo(1);
    }

    @Test
    void successfulRetryWithANewEmailStoresIt() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        identityLinks(HttpStatus.CREATED, "CREATED");

        retry(TestTokens.admin(), pending.getMerchantCode(),
                "{\"email\":\" Other@Example.com \",\"initialPassword\":\"Init1al-Pass!\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.loginEmail").value("other@example.com"));

        assertThat(sentLink(0).get("email")).isEqualTo("other@example.com");
        assertThat(sentLink(0).get("initialPassword")).isEqualTo("Init1al-Pass!");
        assertThat(reload(pending).getLoginEmail()).isEqualTo("other@example.com");
    }

    @Test
    void failedRetryWithANewEmailKeepsTheStoredOne() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        identityRefuses(HttpStatus.CONFLICT, conflict("BUYER_DEACTIVATED"));

        retry(TestTokens.admin(), pending.getMerchantCode(), "{\"email\":\"other@example.com\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("BUYER_DEACTIVATED"))
                .andExpect(jsonPath("$.loginEmail").value(STORED_EMAIL));

        Merchant after = reload(pending);
        assertThat(after.getLoginEmail()).isEqualTo(STORED_EMAIL);
        assertThat(after.getAccountLinkStatus()).isEqualTo(AccountLinkStatus.PENDING);
    }

    @Test
    void alreadyLinkedMerchantIs409WithoutCallingIdentity() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        Merchant linked = reload(pending);
        linked.markLinked(STORED_EMAIL);
        merchants.save(linked);

        retry(TestTokens.admin(), pending.getMerchantCode(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED"));
    }

    @Test
    void emailHeldByAnotherMerchantIs409WithoutCallingIdentity() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);
        Merchant other = savePending("taken@example.com");

        retry(TestTokens.admin(), pending.getMerchantCode(), "{\"email\":\"TAKEN@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOGIN_EMAIL_IN_USE"))
                .andExpect(jsonPath("$.merchantCode").value(other.getMerchantCode()));

        assertThat(reload(pending).getLoginEmail()).isEqualTo(STORED_EMAIL);
    }

    @Test
    void unknownCodeIs404ForAdmin() throws Exception {
        retry(TestTokens.admin(), "MCH-999999", null).andExpect(status().isNotFound());
    }

    @Test
    void nonAdminGets403AndNothingChanges() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);

        retry(TestTokens.buyer(5), pending.getMerchantCode(), null).andExpect(status().isForbidden());
        retry(TestTokens.merchant(5, pending.getId()), pending.getMerchantCode(), null)
                .andExpect(status().isForbidden());

        assertThat(reload(pending).getAccountLinkStatus()).isEqualTo(AccountLinkStatus.PENDING);
    }

    @Test
    void invalidEmailIs400BeforeTheAdminCheck() throws Exception {
        Merchant pending = savePending(STORED_EMAIL);

        retry(TestTokens.admin(), pending.getMerchantCode(), "{\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());
        retry(TestTokens.admin(), pending.getMerchantCode(), "{\"email\":\"   \"}")
                .andExpect(status().isBadRequest());
        retry(TestTokens.buyer(5), pending.getMerchantCode(), "{\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void toStringMasksTheInitialPassword() {
        assertThat(new AccountLinkRequest("a@example.com", "Init1al-Pass!").toString())
                .doesNotContain("Init1al-Pass!")
                .contains("initialPassword=***");
    }
}
