package com.basketry.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

/** POST /stock/{productId}/adjustments (FR-M4, row 6 of the Day 10 review). */
class AdjustEndpointTests extends InventoryServiceTestBase {

    private static final String OWNER = TestTokens.merchant(10, MERCHANT);

    private org.springframework.test.web.servlet.ResultActions adjust(String token, String body) throws Exception {
        return perform(json(bearer(post("/stock/42/adjustments"), token), body));
    }

    @Test
    void theOwnerAdjustsAndGetsTheNewStockAsALong() throws Exception {
        givenStockAndCounter(42, 3_000_000_000L);

        adjust(OWNER, "{\"delta\":-1}")
                .andExpect(status().isOk())
                .andExpect(content().json("{\"productId\":42,\"merchantId\":3,\"quantity\":2999999999}",
                        JsonCompareMode.STRICT));
        assertThat(quantityOf(42)).isEqualTo(2_999_999_999L);
        assertThat(counterOf(42)).hasValue(2_999_999_999L);
    }

    /** Whole JSON numbers only, never converted or truncated; not 0, within the limit; nothing else. */
    @Test
    void anInvalidBodyIsA400AndChangesNothing() throws Exception {
        givenStock(42, 5);
        for (String body : new String[] {"{\"delta\":1.5}", "{\"delta\":2.0}", "{\"delta\":\"5\"}",
                "{\"delta\":true}", "{\"delta\":null}", "{}", "{\"delta\":0}", "{\"delta\":1000001}",
                "{\"delta\":-1000001}", "{\"delta\":9223372036854775808}", "{\"delta\":1", "not json",
                "{\"delta\":1}{\"delta\":1}"}) {
            adjust(OWNER, body).andExpect(status().isBadRequest());
        }
        assertThat(quantityOf(42)).isEqualTo(5);
    }

    @Test
    void anUnknownFieldIsA400NamingIt() throws Exception {
        givenStock(42, 5);

        adjust(OWNER, "{\"delta\":1,\"quantity\":10,\"absolute\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown field(s): absolute, quantity. Only delta is accepted."));
        assertThat(quantityOf(42)).isEqualTo(5);
    }

    /** An invalid request is a 400 for anyone, never a 403. */
    @Test
    void validationComesBeforeTheOwnershipCheck() throws Exception {
        givenStock(42, 5);

        adjust(TestTokens.buyer(20), "{\"delta\":0}").andExpect(status().isBadRequest());
        adjust(TestTokens.merchant(11, MERCHANT + 1), "{\"delta\":1,\"x\":1}").andExpect(status().isBadRequest());
        adjust(TestTokens.admin(), "{\"delta\":1.5}").andExpect(status().isBadRequest());
    }

    @Test
    void aDecreaseBelowZeroIs409WithItsCode() throws Exception {
        givenStock(42, 5);

        adjust(OWNER, "{\"delta\":-6}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_WOULD_GO_NEGATIVE"))
                .andExpect(jsonPath("$.detail").value("Product 42 has 5 in stock; a change of -6 would take it"
                        + " below zero"));
        assertThat(quantityOf(42)).isEqualTo(5);
    }

    @Test
    void anIncreaseAboveTheLimitIs409WithItsCode() throws Exception {
        givenStock(42, 999_999);

        adjust(OWNER, "{\"delta\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));
        assertThat(quantityOf(42)).isEqualTo(999_999);
    }

    /** Admin can view stock but not adjust it (FR-M4 says merchant). */
    @Test
    void anAdminCannotAdjust() throws Exception {
        givenStock(42, 5);

        adjust(TestTokens.admin(), "{\"delta\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only the merchant that owns this product can adjust its stock."));
        assertThat(quantityOf(42)).isEqualTo(5);
    }
}
