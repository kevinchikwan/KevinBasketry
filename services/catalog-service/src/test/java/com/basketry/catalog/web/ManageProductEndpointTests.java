package com.basketry.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Row 5 of the Day 12 review: GET /manage/products and GET /manage/products/{id}. */
class ManageProductEndpointTests extends CatalogServiceTestBase {

    private long tea;
    private long soap;
    private long coffee;

    @BeforeEach
    void twoProductsOfMerchantOneInactiveAndOneOfAnother() {
        tea = givenProduct(MERCHANT, "Tea").id();
        soap = givenProduct(MERCHANT, "Soap").id();
        coffee = givenProduct(OTHER_MERCHANT, "Coffee").id();
        productService.update(MERCHANT, soap, new ProductChanges(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(false)));
        merchantStatuses.apply(MERCHANT, false, 1);
    }

    /**
     * A merchant sees its own, inactive ones too, even while it is itself
     * inactive, with their stock-row status. Mutation: reuse the public
     * visibility rule here.
     */
    @Test
    void aMerchantSeesAllItsOwnProducts() throws Exception {
        perform(bearer(get("/manage/products"), ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", org.hamcrest.Matchers.contains("Tea", "Soap")))
                .andExpect(jsonPath("$.content[1].active").value(false))
                .andExpect(jsonPath("$.content[0].stockRow").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void adminSeesEveryProductOrOneMerchants() throws Exception {
        perform(bearer(get("/manage/products"), TestTokens.admin()))
                .andExpect(jsonPath("$.content[*].name", org.hamcrest.Matchers.contains("Tea", "Soap", "Coffee")));
        perform(bearer(get("/manage/products").param("merchantId", Long.toString(OTHER_MERCHANT)),
                TestTokens.admin())).andExpect(jsonPath("$.content[*].name", org.hamcrest.Matchers.contains("Coffee")));
    }

    /** Mutation: ignore the merchantId parameter for merchants (another merchant's list would leak). */
    @Test
    void aMerchantAskingForAnotherMerchantOrABuyerIs403() throws Exception {
        perform(bearer(get("/manage/products").param("merchantId", Long.toString(OTHER_MERCHANT)), ownerToken()))
                .andExpect(status().isForbidden());
        perform(bearer(get("/manage/products").param("merchantId", Long.toString(MERCHANT)), ownerToken()))
                .andExpect(status().isOk());
        perform(bearer(get("/manage/products"), TestTokens.buyer(20))).andExpect(status().isForbidden());
    }

    /** 400 before 403, for a buyer too. Mutation: drop a bound. */
    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "merchantId=0"})
    void parametersOutOfRangeAre400EvenForABuyer(String parameter) throws Exception {
        String[] pair = parameter.split("=", 2);

        perform(bearer(get("/manage/products").param(pair[0], pair[1]), TestTokens.buyer(20)))
                .andExpect(status().isBadRequest());
    }

    /**
     * The owner gets its product; another merchant, a buyer and a missing id
     * get 403; only admin gets 404. Mutation: answer 404 to a non-admin for a
     * missing product (which ids exist would leak).
     */
    @Test
    void theDetailFollowsInventorysOwnershipRules() throws Exception {
        perform(bearer(get("/manage/products/" + soap), ownerToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        perform(bearer(get("/manage/products/" + coffee), ownerToken())).andExpect(status().isForbidden());
        perform(bearer(get("/manage/products/999999"), ownerToken())).andExpect(status().isForbidden());
        perform(bearer(get("/manage/products/" + tea), TestTokens.buyer(20))).andExpect(status().isForbidden());
        perform(bearer(get("/manage/products/" + coffee), TestTokens.admin())).andExpect(status().isOk());
        perform(bearer(get("/manage/products/999999"), TestTokens.admin())).andExpect(status().isNotFound());
    }
}
