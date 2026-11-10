package com.basketry.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.stockrow.StockRowRelay;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Row 6 of the Day 12 review: GET /internal/products, for order-service's re-validation. */
class InternalProductEndpointTests extends CatalogServiceTestBase {

    @Autowired
    private StockRowRelay relay;

    /**
     * Every flag order-service needs, in id order, each id once, unknown ids
     * left out, hidden products included. Mutations: COALESCE(ms.active,
     * FALSE) here (an unseen merchant refused at checkout); filter by the
     * public visibility rule (order-service could not tell why).
     */
    @Test
    void eachProductCarriesWhatOrderServiceChecks() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        long soap = givenProduct(MERCHANT, "Soap", "1.00").id();
        long coffee = givenProduct(OTHER_MERCHANT, "Coffee", "4.00").id();
        productService.update(MERCHANT, soap, new ProductChanges(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(false)));
        merchantStatuses.apply(OTHER_MERCHANT, false, 1);
        inventory.givenStock(coffee, MERCHANT, 0);
        relay.createPending();

        perform(internal(get("/internal/products").param("ids", coffee + "," + tea + ",999999," + tea + "," + soap)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(tea))
                .andExpect(jsonPath("$.content[1].id").value(soap))
                .andExpect(jsonPath("$.content[2].id").value(coffee))
                .andExpect(jsonPath("$.content[0].merchantId").value(MERCHANT))
                .andExpect(jsonPath("$.content[0].name").value("Tea"))
                .andExpect(jsonPath("$.content[0].price").value(2.50))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].merchantActive").value(true))
                .andExpect(jsonPath("$.content[0].stockRow").value("CREATED"))
                .andExpect(jsonPath("$.content[1].active").value(false))
                .andExpect(jsonPath("$.content[2].merchantActive").value(false))
                .andExpect(jsonPath("$.content[2].stockRow").value("CONFLICT"))
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
    }

    /** All four statuses reach order-service: REJECTED too. */
    @Test
    void aRejectedStockRowIsReported() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea").id();
        inventory.answerCreatesWith(400);
        relay.createPending();

        perform(internal(get("/internal/products").param("ids", Long.toString(tea))))
                .andExpect(jsonPath("$.content[0].stockRow").value("REJECTED"));
    }

    /** Mutation: drop a bound (101 ids, or 0, accepted). */
    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "1,0", "abc", "1,,2"})
    void invalidIdsAre400(String ids) throws Exception {
        perform(internal(get("/internal/products").param("ids", ids))).andExpect(status().isBadRequest());
    }

    @Test
    void oneHundredIdsAreAcceptedAndOneHundredAndOneAreNot() throws Exception {
        StringBuilder ids = new StringBuilder("1");
        for (int i = 2; i <= 100; i++) {
            ids.append(',').append(i);
        }

        perform(internal(get("/internal/products").param("ids", ids.toString()))).andExpect(status().isOk());
        perform(internal(get("/internal/products").param("ids", ids + ",101"))).andExpect(status().isBadRequest());
        perform(internal(get("/internal/products"))).andExpect(status().isBadRequest());
    }

    /** The limit counts the list as sent, repeats included. Mutation: count the ids after removing repeats. */
    @Test
    void oneHundredAndOneRepeatsOfOneIdAre400() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea").id();
        String ids = String.join(",", java.util.Collections.nCopies(101, Long.toString(tea)));

        perform(internal(get("/internal/products").param("ids", ids))).andExpect(status().isBadRequest());
    }
}
