package com.basketry.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
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
 * Row 13 of the Day 12 review: every endpoint against every kind of caller, as
 * in inventory-service, with valid requests, so the status is decided by who is
 * asking. Product {@link #ownProduct} belongs to merchant 3 (the OWNER), product
 * {@link #othersProduct} to merchant 4, 999999 does not exist. After each case,
 * nothing changed unless the case succeeded. Each status is a named mutation:
 * flipping who may call an endpoint fails at least one cell.
 */
class AuthorizationMatrixTests extends CatalogServiceTestBase {

    private static long ownProduct;
    private static long othersProduct;

    enum Caller {
        OWNER(r -> bearer(r, TestTokens.merchant(10, MERCHANT))),
        OTHER_MERCHANT(r -> bearer(r, TestTokens.merchant(11, CatalogServiceTestBase.OTHER_MERCHANT))),
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

    record Endpoint(String name, Supplier<MockHttpServletRequestBuilder> request, int... statuses) {
    }

    static List<Endpoint> endpoints() {
        String product = "{\"name\":\"New\",\"category\":\"Drinks\",\"price\":1.00}";
        //                                                                          OWN  OTH  BUY  ADM  NONE KEY  WRONG ADM+KEY
        return List.of(
                new Endpoint("GET /products", () -> get("/products"),                 200, 200, 200, 200, 200, 200, 200, 200),
                new Endpoint("GET /products/own", () -> get("/products/" + ownProduct),
                                                                                      200, 200, 200, 200, 200, 200, 200, 200),
                new Endpoint("GET /products/missing", () -> get("/products/999999"),  404, 404, 404, 404, 404, 404, 404, 404),
                new Endpoint("POST /products", () -> json(post("/products"), product),
                                                                                      201, 201, 403, 403, 401, 401, 401, 403),
                new Endpoint("PATCH /products/own", () -> json(patch("/products/" + ownProduct), "{\"price\":9.99}"),
                                                                                      200, 403, 403, 403, 401, 401, 401, 403),
                new Endpoint("PATCH /products/missing", () -> json(patch("/products/999999"), "{\"price\":9.99}"),
                                                                                      403, 403, 403, 403, 401, 401, 401, 403),
                new Endpoint("GET /manage/products", () -> get("/manage/products"),   200, 200, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /manage/products?merchantId=3",
                        () -> get("/manage/products").param("merchantId", "3"),       200, 403, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /manage/products/own", () -> get("/manage/products/" + ownProduct),
                                                                                      200, 403, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /manage/products/others", () -> get("/manage/products/" + othersProduct),
                                                                                      403, 200, 403, 200, 401, 401, 401, 200),
                new Endpoint("GET /manage/products/missing", () -> get("/manage/products/999999"),
                                                                                      403, 403, 403, 404, 401, 401, 401, 404),
                new Endpoint("GET /internal/products", () -> get("/internal/products").param("ids", "1,2"),
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
        ownProduct = givenProduct(MERCHANT, "Tea", "2.50").id();
        othersProduct = givenProduct(OTHER_MERCHANT, "Coffee", "2.50").id();
    }

    @ParameterizedTest(name = "{0} as {1} -> {3}")
    @MethodSource("cases")
    void eachCallerGetsItsStatusAndNothingChangesUnlessAllowed(String endpoint, Caller caller,
                                                                Supplier<MockHttpServletRequestBuilder> request,
                                                                int expected) throws Exception {
        perform(caller.credentials.apply(request.get())).andExpect(status().is(expected));

        boolean patched = endpoint.equals("PATCH /products/own") && expected == 200;
        boolean created = endpoint.equals("POST /products") && expected == 201;
        assertThat(products.findById(ownProduct).orElseThrow().getPrice())
                .isEqualByComparingTo(patched ? "9.99" : "2.50");
        assertThat(products.findById(othersProduct).orElseThrow().getPrice()).isEqualByComparingTo("2.50");
        assertThat(products.count()).isEqualTo(created ? 3 : 2);
    }
}
