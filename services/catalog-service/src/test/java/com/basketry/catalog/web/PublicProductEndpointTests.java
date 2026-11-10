package com.basketry.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rows 5 and 7 of the Day 12 review: GET /products and GET /products/{id}, public. */
class PublicProductEndpointTests extends CatalogServiceTestBase {

    private static ProductChanges active(boolean value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(value));
    }

    @Test
    void theListingHasThePagingShapeAndEachProductItsAvailability() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        givenProduct(MERCHANT, "Coffee", "4.00");
        inventory.givenStock(tea, MERCHANT, 5);

        perform(get("/products").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tea))
                .andExpect(jsonPath("$.content[0].merchantId").value(MERCHANT))
                .andExpect(jsonPath("$.content[0].name").value("Tea"))
                .andExpect(jsonPath("$.content[0].description").value("A description"))
                .andExpect(jsonPath("$.content[0].category").value("Groceries"))
                .andExpect(jsonPath("$.content[0].price").value(2.50))
                .andExpect(jsonPath("$.content[0].availability").value("IN_STOCK"))
                .andExpect(jsonPath("$.content[0].active").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void theFiltersAreApplied() throws Exception {
        givenProduct(MERCHANT, "Tea");
        givenProduct(OTHER_MERCHANT, "Coffee");

        perform(get("/products").param("merchantId", Long.toString(OTHER_MERCHANT)))
                .andExpect(jsonPath("$.content[*].name", org.hamcrest.Matchers.contains("Coffee")));
        perform(get("/products").param("category", "Groceries"))
                .andExpect(jsonPath("$.totalElements").value(2));
        perform(get("/products").param("category", "Toys")).andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Mutation: drop a bound (for example size up to 1000, or a negative page). */
    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "merchantId=0", "category=", "page=abc",
            "category=ccccccccccccccccccccccccccccccccccccccccccccccccccc"})
    void parametersOutOfRangeAre400(String parameter) throws Exception {
        String[] pair = parameter.split("=", 2);

        perform(get("/products").param(pair[0], pair[1])).andExpect(status().isBadRequest());
    }

    @Test
    void theDetailHasTheListingsFieldsAndItsAvailability() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();

        perform(get("/products/" + tea))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tea))
                .andExpect(jsonPath("$.price").value(2.50))
                .andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"));
        inventory.givenStock(tea, MERCHANT, 1);
        perform(get("/products/" + tea)).andExpect(jsonPath("$.availability").value("IN_STOCK"));
    }

    /**
     * The approved reading of FR-B3 with FR-B6: a hidden product answers 404,
     * like a missing one. Mutations: the detail ignores product.active; the
     * detail ignores the merchant's status.
     */
    @Test
    void aHiddenProductIs404LikeAMissingOne() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea").id();
        long coffee = givenProduct(OTHER_MERCHANT, "Coffee").id();
        productService.update(MERCHANT, tea, active(false));
        merchantStatuses.apply(OTHER_MERCHANT, false, 1);

        perform(get("/products/" + tea)).andExpect(status().isNotFound());
        perform(get("/products/" + coffee)).andExpect(status().isNotFound());
        perform(get("/products/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        perform(get("/products/0")).andExpect(status().isBadRequest());
    }

    /**
     * The public chain never reads a token: an expired, a garbage, or a
     * foreign-key token still gets 200 on both reads, so the Buyer app is never
     * logged out by browsing. Mutation: put the resource server in the public
     * chain (each of these would be a 401).
     */
    @Test
    void expiredOrGarbageTokensAreIgnoredByThePublicReads() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea").id();
        String foreignKey = TestTokens.sign(TestTokens.newKey(), 1, List.of("BUYER"), null,
                Instant.now().plus(Duration.ofHours(1)));

        for (String token : List.of(TestTokens.expiredMerchant(10, MERCHANT), "garbage", foreignKey)) {
            perform(bearer(get("/products"), token)).andExpect(status().isOk());
            perform(bearer(get("/products/" + tea), token)).andExpect(status().isOk());
        }
        perform(get("/products/" + tea).header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isOk());
    }
}
