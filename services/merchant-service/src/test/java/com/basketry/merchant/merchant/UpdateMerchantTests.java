package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PATCH /merchants/{code}: Admin changes commissionRate and/or kycVerified. At
 * least one field is required; nulls, unknown fields and booleans that are not
 * real JSON booleans are a 400. MerchantStatusTests covers {@code active}.
 */
class UpdateMerchantTests extends MerchantServiceTestBase {

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = savePending("shop@example.com"); // commissionRate 5.00, kycVerified true, active true
    }

    private ResultActions update(String token, String code, String body) throws Exception {
        return mockMvc.perform(bearer(patch("/merchants/{code}", code), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Merchant reload() {
        return merchants.findById(merchant.getId()).orElseThrow();
    }

    private void assertUnchanged() {
        Merchant after = reload();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("5.00");
        assertThat(after.isKycVerified()).isTrue();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getStatusVersion()).isZero();
    }

    @Test
    void commissionRateOnly() throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), "{\"commissionRate\":7.25}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantCode").value(merchant.getMerchantCode()))
                .andExpect(jsonPath("$.commissionRate").value(7.25))
                .andExpect(jsonPath("$.kycVerified").value(true))
                .andExpect(jsonPath("$.active").value(true));

        Merchant after = reload();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("7.25");
        assertThat(after.isKycVerified()).isTrue();
    }

    @Test
    void kycVerifiedOnlyAndActiveIsUntouched() throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), "{\"kycVerified\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycVerified").value(false))
                .andExpect(jsonPath("$.commissionRate").value(5.0))
                .andExpect(jsonPath("$.active").value(true));

        Merchant after = reload();
        assertThat(after.isKycVerified()).isFalse();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("5.00");
    }

    @Test
    void bothFields() throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), "{\"commissionRate\":0,\"kycVerified\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionRate").value(0.0))
                .andExpect(jsonPath("$.kycVerified").value(false));
    }

    @Test
    void sameValuesAreA200NoOp() throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), "{\"commissionRate\":5.00,\"kycVerified\":true}")
                .andExpect(status().isOk());

        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"commissionRate\":null}",
            "{\"kycVerified\":null}",
            "{\"commissionRate\":6,\"kycVerified\":null}",
            "{\"active\":null}",
            "{\"active\":\"false\"}",
            "{\"active\":\"true\"}",
            "{\"active\":0}",
            "{\"active\":1}",
            "{\"commissionRate\":6,\"active\":\"false\"}",
            "{\"name\":\"Renamed\"}",
            "{\"commissionRate\":-0.01}",
            "{\"commissionRate\":100.01}",
            "{\"commissionRate\":5.005}",
            "{\"commissionRate\":\"6.00\"}",
            "{\"kycVerified\":\"maybe\"}",
            "{\"kycVerified\":\"false\"}",
            "{\"kycVerified\":\"true\"}",
            "{\"kycVerified\":0}",
            "{\"kycVerified\":1}",
            "{\"commissionRate\":",
            "[]"})
    void invalidBodyIs400EvenForNonAdminsAndNothingChanges(String body) throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), body).andExpect(status().isBadRequest());
        update(TestTokens.buyer(5), merchant.getMerchantCode(), body).andExpect(status().isBadRequest());

        assertUnchanged();
    }

    @Test
    void unknownFieldsAreNamedInTheError() throws Exception {
        update(TestTokens.admin(), merchant.getMerchantCode(), "{\"category\":\"y\",\"active\":false,\"name\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("category, name")));

        assertUnchanged();
    }

    @Test
    void nonAdminGets403AndNothingChanges() throws Exception {
        update(TestTokens.buyer(5), merchant.getMerchantCode(), "{\"commissionRate\":9}")
                .andExpect(status().isForbidden());
        update(TestTokens.merchant(5, merchant.getId()), merchant.getMerchantCode(), "{\"commissionRate\":9}")
                .andExpect(status().isForbidden());

        assertUnchanged();
    }

    @Test
    void unknownCodeIs404ForAdmin() throws Exception {
        update(TestTokens.admin(), "MCH-999999", "{\"commissionRate\":9}").andExpect(status().isNotFound());
    }

    @Test
    void noTokenGets401() throws Exception {
        mockMvc.perform(patch("/merchants/{code}", merchant.getMerchantCode())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":9}"))
                .andExpect(status().isUnauthorized());

        assertUnchanged();
    }
}
