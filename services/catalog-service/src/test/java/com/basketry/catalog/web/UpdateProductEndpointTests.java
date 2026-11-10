package com.basketry.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.Product;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Row 5 of the Day 12 review: PATCH /products/{id}, a merchant's own products only. */
class UpdateProductEndpointTests extends CatalogServiceTestBase {

    private long tea;

    @BeforeEach
    void aProductOfMerchant() {
        tea = givenProduct(MERCHANT, "Tea", "2.50").id();
    }

    private Product stored() {
        return products.findById(tea).orElseThrow();
    }

    /** Omitted means unchanged. Mutation: apply an omitted field as null or as its default. */
    @Test
    void onlyTheGivenFieldsChange() throws Exception {
        perform(json(bearer(patch("/products/" + tea), ownerToken()), "{\"price\":3.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(3.00))
                .andExpect(jsonPath("$.name").value("Tea"))
                .andExpect(jsonPath("$.description").value("A description"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.stockRow").value("PENDING"));

        assertThat(stored().getPrice()).isEqualByComparingTo("3.00");
        assertThat(stored().getName()).isEqualTo("Tea");
    }

    /** Mutation: treat "" as "no change" (a description could never be cleared). */
    @Test
    void anEmptyDescriptionClearsIt() throws Exception {
        perform(json(bearer(patch("/products/" + tea), ownerToken()), "{\"description\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value(""));
    }

    /** FR-M2: deactivate hides it from buyers; reactivate shows it again. */
    @Test
    void activeFalseDeactivatesAndTrueReactivates() throws Exception {
        perform(json(bearer(patch("/products/" + tea), ownerToken()), "{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        perform(get("/products/" + tea)).andExpect(status().isNotFound());

        perform(json(bearer(patch("/products/" + tea), ownerToken()), "{\"active\":true}"))
                .andExpect(status().isOk());
        perform(get("/products/" + tea)).andExpect(status().isOk());
    }

    /** Mutation: treat an explicit null as "no change" (or active null as false). */
    @Test
    void anExplicitNullIs400NamingEveryOne() throws Exception {
        perform(json(bearer(patch("/products/" + tea), ownerToken()), "{\"price\":null,\"active\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("price, active")));

        assertThat(stored().isActive()).isTrue();
    }

    /** Mutations: ignore unknown fields; accept an empty body as a no-op. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"merchantId\":4}", "{\"price\":3.00,\"stock\":5}", "{}", "{\"active\":\"false\"}",
            "{\"active\":0}", "{\"price\":\"3.00\"}", "{\"name\":\"\"}", "{\"price\":0}", "{\"name\":7}"})
    void anInvalidChangeIs400AndChangesNothing(String body) throws Exception {
        perform(json(bearer(patch("/products/" + tea), ownerToken()), body)).andExpect(status().isBadRequest());

        assertThat(stored().getPrice()).isEqualByComparingTo("2.50");
        assertThat(stored().isActive()).isTrue();
    }

    /** 400 before 403. Mutation: check ownership first (another merchant would get 403 here). */
    @Test
    void anInvalidChangeIs400EvenForAnotherMerchant() throws Exception {
        perform(json(bearer(patch("/products/" + tea), otherMerchantToken()), "{}"))
                .andExpect(status().isBadRequest());
    }

    /** FR-M3: another merchant's product and a missing one alike, and nothing changes. */
    @Test
    void anotherMerchantsProductAndAMissingOneAre403() throws Exception {
        perform(json(bearer(patch("/products/" + tea), otherMerchantToken()), "{\"price\":0.01}"))
                .andExpect(status().isForbidden());
        perform(json(bearer(patch("/products/999999"), ownerToken()), "{\"price\":0.01}"))
                .andExpect(status().isForbidden());
        perform(json(bearer(patch("/products/" + tea), TestTokens.admin()), "{\"price\":0.01}"))
                .andExpect(status().isForbidden());

        assertThat(stored().getPrice()).isEqualByComparingTo("2.50");
    }

    /**
     * PATCH on the path the public chain serves for GET still needs a token.
     * Mutation: make the public chain match /products/{id} for every method.
     */
    @Test
    void withoutATokenItIs401() throws Exception {
        perform(json(patch("/products/" + tea), "{\"price\":0.01}")).andExpect(status().isUnauthorized());
        perform(json(bearer(patch("/products/" + tea), TestTokens.expiredMerchant(10, MERCHANT)),
                "{\"price\":0.01}")).andExpect(status().isUnauthorized());

        assertThat(stored().getPrice()).isEqualByComparingTo("2.50");
    }
}
