package com.basketry.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TestTokens;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

/** POST and GET /internal/stock, for catalog-service (rows 5 and 7 of the Day 10 review). */
class InternalStockEndpointTests extends InventoryServiceTestBase {

    private org.springframework.test.web.servlet.ResultActions create(String body) throws Exception {
        return perform(json(internal(post("/internal/stock")), body));
    }

    @Test
    void createIs201ThenTheSameMerchantGets200AndAnotherMerchant409() throws Exception {
        create("{\"productId\":42,\"merchantId\":3}")
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"productId\":42,\"merchantId\":3,\"quantity\":0,\"outcome\":\"CREATED\"}",
                        JsonCompareMode.STRICT));
        create("{\"productId\":42,\"merchantId\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_EXISTS"));
        create("{\"productId\":42,\"merchantId\":4}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_OWNED_BY_OTHER_MERCHANT"));
        assertThat(stockLevels.findById(42L).orElseThrow().getMerchantId()).isEqualTo(3);
    }

    @Test
    void anInvalidCreateIsA400AndCreatesNothing() throws Exception {
        for (String body : new String[] {"{\"productId\":42}", "{\"merchantId\":3}", "{\"productId\":0,\"merchantId\":3}",
                "{\"productId\":42,\"merchantId\":-1}", "{\"productId\":\"42\",\"merchantId\":3}",
                "{\"productId\":42.0,\"merchantId\":3}", "{\"productId\":42,\"merchantId\":null}"}) {
            create(body).andExpect(status().isBadRequest());
        }
        create("{\"productId\":42,\"merchantId\":3,\"quantity\":5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown field(s): quantity. Only productId and merchantId are"
                        + " accepted; a new product starts at 0."));
        assertThat(stockLevels.count()).isZero();
    }

    /** Unknown products are left out, repeats answered once, in product order, quantities as longs. */
    @Test
    void availabilityAnswersTheKnownProducts() throws Exception {
        givenStock(41, 0);
        givenStock(42, 3_000_000_000L);

        perform(internal(get("/internal/stock").param("productIds", "42,999,41,42")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[{\"productId\":41,\"quantity\":0},"
                        + "{\"productId\":42,\"quantity\":3000000000}]}", JsonCompareMode.STRICT));
    }

    @Test
    void availabilityTakesOneToAHundredPositiveIds() throws Exception {
        String hundred = LongStream.rangeClosed(1, 100).mapToObj(Long::toString).collect(Collectors.joining(","));
        perform(internal(get("/internal/stock").param("productIds", hundred))).andExpect(status().isOk());
        for (String ids : new String[] {"", "0", "-1", "abc", hundred + ",101"}) {
            perform(internal(get("/internal/stock").param("productIds", ids))).andExpect(status().isBadRequest());
        }
        perform(internal(get("/internal/stock"))).andExpect(status().isBadRequest());
    }

    /** No key, a wrong key, or only a user JWT (even an admin's): the same bare 401, nothing created. */
    @Test
    void withoutTheKeyEverythingIsABare401() throws Exception {
        String body = "{\"productId\":42,\"merchantId\":3}";
        perform(json(post("/internal/stock"), body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        perform(json(post("/internal/stock").header("X-Internal-Api-Key", API_KEY + "x"), body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        perform(json(bearer(post("/internal/stock"), TestTokens.admin()), body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        perform(bearer(get("/internal/stock").param("productIds", "42"), TestTokens.admin()))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        assertThat(stockLevels.count()).isZero();
    }

    /** A browser never gets CORS permission for /internal/**. */
    @Test
    void internalEndpointsSendNoCorsHeaders() throws Exception {
        perform(internal(get("/internal/stock").param("productIds", "42")).header("Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
