package com.basketry.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.Product;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Row 5 of the Day 12 review: POST /products, a merchant's own products only. */
class CreateProductEndpointTests extends CatalogServiceTestBase {

    private static final String VALID = "{\"name\":\"Tea\",\"description\":\"Green\",\"category\":\"Drinks\","
            + "\"price\":2.50}";

    /**
     * 201, the owner from the token, active, stock row PENDING, Location at
     * /manage. Mutation: take the owner from anywhere but the token's claim.
     */
    @Test
    void aMerchantCreatesItsOwnProduct() throws Exception {
        String location = perform(json(bearer(post("/products"), ownerToken()), VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value(MERCHANT))
                .andExpect(jsonPath("$.name").value("Tea"))
                .andExpect(jsonPath("$.price").value(2.50))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.stockRow").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getHeader("Location");

        Product stored = products.findAll().getFirst();
        assertThat(location).isEqualTo("/manage/products/" + stored.getId());
        assertThat(stored.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(stockRowRequestOf(stored.getId()).getMerchantId()).isEqualTo(MERCHANT);
    }

    /** Mutation: ignore unknown fields (a merchantId or active in the body would look applied). */
    @Test
    void anUnknownFieldIs400NamingIt() throws Exception {
        perform(json(bearer(post("/products"), ownerToken()),
                "{\"name\":\"Tea\",\"category\":\"Drinks\",\"price\":2.50,\"merchantId\":4,\"active\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("active, merchantId")));

        assertThat(products.count()).isZero();
    }

    /** Every field rule at once, in violations. Mutation: drop the field check from the request. */
    @Test
    void brokenFieldRulesAre400NamingEachOne() throws Exception {
        perform(json(bearer(post("/products"), ownerToken()), "{\"name\":\" \",\"price\":9.999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations").value(org.hamcrest.Matchers.contains("name is required.",
                        "category is required.", "price must have at most 2 decimal places.")));
    }

    /**
     * A field takes only its own JSON type: no conversion. Mutations: the
     * plain BigDecimal deserializer ("2.50" accepted); scalar coercion back on
     * (5 accepted as a name).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"Tea\",\"category\":\"Drinks\",\"price\":\"2.50\"}",
            "{\"name\":5,\"category\":\"Drinks\",\"price\":2.50}",
            "{\"name\":\"Tea\",\"category\":true,\"price\":2.50}",
            "{\"name\":\"Tea\",\"category\":\"Drinks\",\"price\":2.50,\"description\":7}",
            "{\"name\":\"Tea\",",
            "[]"})
    void aWrongJsonTypeOrMalformedJsonIs400(String body) throws Exception {
        perform(json(bearer(post("/products"), ownerToken()), body)).andExpect(status().isBadRequest());

        assertThat(products.count()).isZero();
    }

    /** 400 before 403: a buyer with an invalid body learns nothing more. Mutation: check the capability first. */
    @Test
    void anInvalidBodyIs400EvenForABuyer() throws Exception {
        perform(json(bearer(post("/products"), TestTokens.buyer(20)), "{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Admin reads everything but creates nothing. Mutation: let any authenticated caller create. */
    @Test
    void aBuyerOrAnAdminIs403() throws Exception {
        perform(json(bearer(post("/products"), TestTokens.buyer(20)), VALID)).andExpect(status().isForbidden());
        perform(json(bearer(post("/products"), TestTokens.admin()), VALID)).andExpect(status().isForbidden());

        assertThat(products.count()).isZero();
    }

    /**
     * POST on the path the public chain serves for GET still needs a token.
     * Mutation: make the public chain match /products for every method.
     */
    @Test
    void withoutATokenItIs401() throws Exception {
        perform(json(post("/products"), VALID)).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        perform(json(bearer(post("/products"), "garbage"), VALID)).andExpect(status().isUnauthorized());

        assertThat(products.count()).isZero();
    }
}
