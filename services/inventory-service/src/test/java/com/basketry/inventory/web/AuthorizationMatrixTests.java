package com.basketry.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Row 7 of the Day 10 review: every endpoint against every kind of caller,
 * with valid requests, so the status is decided by who is asking. Product 42
 * belongs to merchant 3 (the OWNER), product 43 to merchant 4 (OTHER), 999
 * does not exist. After each case, nothing changed unless the case succeeded.
 */
class AuthorizationMatrixTests extends InventoryServiceTestBase {

    enum Caller {
        OWNER(r -> bearer(r, TestTokens.merchant(10, MERCHANT))),
        OTHER_MERCHANT(r -> bearer(r, TestTokens.merchant(11, MERCHANT + 1))),
        BUYER(r -> bearer(r, TestTokens.buyer(20))),
        ADMIN(r -> bearer(r, TestTokens.admin())),
        NO_TOKEN(r -> r),
        INTERNAL_KEY(r -> r.header("X-Internal-Api-Key", API_KEY)),
        WRONG_KEY(r -> r.header("X-Internal-Api-Key", "wrong-key-of-at-least-32-characters-0000")),
        ADMIN_AND_KEY(r -> r.header("X-Internal-Api-Key", API_KEY)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin()));

        final Function<MockHttpServletRequestBuilder, MockHttpServletRequestBuilder> credentials;

        Caller(Function<MockHttpServletRequestBuilder, MockHttpServletRequestBuilder> credentials) {
            this.credentials = credentials;
        }
    }

    /** One endpoint, and the status each caller gets, in Caller order. */
    record Endpoint(String name, Supplier<MockHttpServletRequestBuilder> request, int... statuses) {
    }

    static List<Endpoint> endpoints() {
        //                                                                 OWN  OTH  BUY  ADM  NONE KEY  WRONG ADM+KEY
        return List.of(
                new Endpoint("GET /stock", () -> get("/stock"),                200, 200, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /stock?merchantId=3", () -> get("/stock").param("merchantId", "3"),
                                                                               200, 403, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /stock/42", () -> get("/stock/42"),          200, 403, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /stock/999", () -> get("/stock/999"),        403, 403, 403, 404, 401, 401, 401, 404),
                new Endpoint("POST /stock/42/adjustments", () -> json(post("/stock/42/adjustments"), "{\"delta\":1}"),
                                                                               200, 403, 403, 403, 401, 401, 401, 403),
                new Endpoint("POST /stock/999/adjustments", () -> json(post("/stock/999/adjustments"), "{\"delta\":1}"),
                                                                               403, 403, 403, 403, 401, 401, 401, 403),
                new Endpoint("POST /internal/stock", () -> json(post("/internal/stock"),
                        "{\"productId\":50,\"merchantId\":3}"),                401, 401, 401, 401, 401, 201, 401, 201),
                new Endpoint("GET /internal/stock", () -> get("/internal/stock").param("productIds", "42,43"),
                                                                               401, 401, 401, 401, 401, 200, 401, 200));
    }

    static List<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            for (Caller caller : Caller.values()) {
                cases.add(Arguments.of(endpoint.name(), caller, endpoint.request(),
                        endpoint.statuses()[caller.ordinal()]));
            }
        }
        return cases;
    }

    @BeforeEach
    void oneProductForEachMerchant() {
        givenStock(42, 5);
        jdbc.update("INSERT INTO stock_level (product_id, merchant_id, quantity, created_at, updated_at)"
                + " VALUES (43, 4, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @ParameterizedTest(name = "{0} as {1} -> {3}")
    @MethodSource("cases")
    void eachCallerGetsItsStatusAndNothingChangesUnlessAllowed(String endpoint, Caller caller,
                                                                Supplier<MockHttpServletRequestBuilder> request,
                                                                int expected) throws Exception {
        perform(caller.credentials.apply(request.get())).andExpect(status().is(expected));

        boolean adjusted = endpoint.equals("POST /stock/42/adjustments") && expected == 200;
        boolean created = endpoint.equals("POST /internal/stock") && expected == 201;
        assertThat(quantityOf(42)).isEqualTo(adjusted ? 6 : 5);
        assertThat(quantityOf(43)).isEqualTo(5);
        assertThat(stockLevels.existsById(50L)).isEqualTo(created);
        assertThat(stockLevels.existsById(999L)).isFalse();
    }
}
