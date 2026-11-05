package com.basketry.merchant.merchant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /merchants (FR-A2: Admin only, paged like GET /accounts) and
 * GET /merchants/{code} (Admin, or the merchant itself).
 */
class GetMerchantsTests extends MerchantServiceTestBase {

    private Merchant first;
    private Merchant second;
    private Merchant third;

    @BeforeEach
    void setUp() {
        first = savePending("first@example.com");
        second = savePending("second@example.com");
        third = savePending("third@example.com");
    }

    private ResultActions list(String token, String query) throws Exception {
        return mockMvc.perform(bearer(get("/merchants" + query), token));
    }

    private ResultActions getOne(String token, String code) throws Exception {
        return mockMvc.perform(bearer(get("/merchants/{code}", code), token));
    }

    // --- GET /merchants ------------------------------------------------------

    @Test
    void adminGetsFirstPageInIdOrderWithAllFields() throws Exception {
        list(TestTokens.admin(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content[0].merchantCode").value(first.getMerchantCode()))
                .andExpect(jsonPath("$.content[0].name").value("Corner Shop"))
                .andExpect(jsonPath("$.content[0].category").value("Groceries"))
                .andExpect(jsonPath("$.content[0].kycVerified").value(true))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].commissionRate").value(5.0))
                .andExpect(jsonPath("$.content[0].loginEmail").value("first@example.com"))
                .andExpect(jsonPath("$.content[0].accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].id").value(second.getId()))
                .andExpect(jsonPath("$.content[2].id").value(third.getId()));
    }

    @Test
    void pagingSplitsTheList() throws Exception {
        list(TestTokens.admin(), "?page=1&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(third.getId()));
    }

    @Test
    void pagePastTheEndIsEmpty() throws Exception {
        list(TestTokens.admin(), "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?size=-5", "?page=abc", "?size=1.5"})
    void outOfRangePagingIs400EvenForNonAdmins(String query) throws Exception {
        list(TestTokens.admin(), query).andExpect(status().isBadRequest());
        list(TestTokens.buyer(5), query).andExpect(status().isBadRequest());
    }

    @Test
    void sizeOneHundredIsAllowed() throws Exception {
        list(TestTokens.admin(), "?size=100").andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotList() throws Exception {
        list(TestTokens.buyer(5), "").andExpect(status().isForbidden());
        list(TestTokens.merchant(5, first.getId()), "").andExpect(status().isForbidden());
    }

    @Test
    void noTokenGets401() throws Exception {
        mockMvc.perform(get("/merchants")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/merchants/{code}", first.getMerchantCode())).andExpect(status().isUnauthorized());
    }

    // --- GET /merchants/{code} -----------------------------------------------

    @Test
    void adminGetsAnyMerchant() throws Exception {
        getOne(TestTokens.admin(), second.getMerchantCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second.getId()))
                .andExpect(jsonPath("$.merchantCode").value(second.getMerchantCode()))
                .andExpect(jsonPath("$.loginEmail").value("second@example.com"))
                .andExpect(jsonPath("$.accountOutcome").doesNotExist());
    }

    @Test
    void adminGets404ForAnUnknownCode() throws Exception {
        getOne(TestTokens.admin(), "MCH-999999").andExpect(status().isNotFound());
    }

    @Test
    void merchantGetsItself() throws Exception {
        getOne(TestTokens.merchant(42, second.getId()), second.getMerchantCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second.getId()));
    }

    @Test
    void merchantGets403ForAnotherMerchantAndForAnUnknownCodeAlike() throws Exception {
        String token = TestTokens.merchant(42, second.getId());

        getOne(token, first.getMerchantCode()).andExpect(status().isForbidden());
        getOne(token, "MCH-999999").andExpect(status().isForbidden());
    }

    @Test
    void buyerGets403() throws Exception {
        getOne(TestTokens.buyer(5), first.getMerchantCode()).andExpect(status().isForbidden());
    }

    @Test
    void merchantIdClaimWithoutTheMerchantCapabilityIsNotEnough() throws Exception {
        String token = TestTokens.sign(TestTokens.KEY, 42, List.of("BUYER"), first.getId(),
                Instant.now().plus(Duration.ofHours(1)));

        getOne(token, first.getMerchantCode()).andExpect(status().isForbidden());
    }
}
