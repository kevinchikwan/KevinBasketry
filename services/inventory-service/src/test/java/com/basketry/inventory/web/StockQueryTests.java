package com.basketry.inventory.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

/** GET /stock and GET /stock/{productId} (FR-M4; DESIGN.md Section 3, inventory-service). */
class StockQueryTests extends InventoryServiceTestBase {

    private static final String OWNER = TestTokens.merchant(10, MERCHANT);

    @BeforeEach
    void stockOfTwoMerchants() {
        givenStock(41, 3);
        givenStock(43, 5);
        givenStock(42, 3_000_000_000L); // above an int: proves the long all the way to JSON
        jdbc.update("INSERT INTO stock_level (product_id, merchant_id, quantity, created_at, updated_at)"
                + " VALUES (50, 4, 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @Test
    void aMerchantGetsItsOwnProductWithTheQuantityAsALong() throws Exception {
        perform(bearer(get("/stock/42"), OWNER))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"productId\":42,\"merchantId\":3,\"quantity\":3000000000}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void aMerchantListsOnlyItsOwnProductsInProductOrder() throws Exception {
        perform(bearer(get("/stock"), OWNER))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"productId":41,"merchantId":3,"quantity":3},
                                    {"productId":42,"merchantId":3,"quantity":3000000000},
                                    {"productId":43,"merchantId":3,"quantity":5}],
                         "page":0,"size":20,"totalElements":3,"totalPages":1}""", JsonCompareMode.STRICT));
    }

    @Test
    void anAdminListsEveryProductOrOneMerchants() throws Exception {
        perform(bearer(get("/stock"), TestTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[3].productId").value(50));
        perform(bearer(get("/stock").param("merchantId", "4"), TestTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].merchantId").value(4));
    }

    @Test
    void pagingWorksAndAPagePastTheEndIsEmpty() throws Exception {
        perform(bearer(get("/stock").param("page", "1").param("size", "2"), OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(43))
                .andExpect(jsonPath("$.totalPages").value(2));
        perform(bearer(get("/stock").param("page", "5"), OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /** Out of range is a 400, never clamped; also for a caller who would get 403. */
    @Test
    void invalidParametersAreA400BeforeAnyCapabilityCheck() throws Exception {
        String buyer = TestTokens.buyer(20);
        for (String[] param : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"merchantId", "0"},
                {"page", "x"}}) {
            perform(bearer(get("/stock").param(param[0], param[1]), buyer)).andExpect(status().isBadRequest());
        }
        perform(bearer(get("/stock/0"), buyer)).andExpect(status().isBadRequest());
        perform(bearer(get("/stock/abc"), buyer)).andExpect(status().isBadRequest());
    }

    /** The merchantId claim counts only with the MERCHANT capability (identity drops MERCHANT when deactivated). */
    @Test
    void aMerchantIdClaimWithoutTheMerchantCapabilityGivesNoAccess() throws Exception {
        String odd = TestTokens.sign(TestTokens.KEY, 10, java.util.List.of("BUYER"), MERCHANT,
                java.time.Instant.now().plusSeconds(3600));

        perform(bearer(get("/stock/42"), odd)).andExpect(status().isForbidden());
        perform(bearer(get("/stock"), odd)).andExpect(status().isForbidden());
    }

    /** Only an admin can tell a missing product from another merchant's. */
    @Test
    void aMissingProductIs404ForAnAdminAnd403ForAMerchant() throws Exception {
        perform(bearer(get("/stock/999"), TestTokens.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        perform(bearer(get("/stock/999"), OWNER)).andExpect(status().isForbidden());
        perform(bearer(get("/stock/50"), OWNER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only an admin or the merchant that owns the stock can view it."));
    }
}
