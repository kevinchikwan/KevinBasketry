package com.basketry.order.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The endpoint tests: the coordinator's test setup (a clock the test moves, the
 * standard order) plus MockMvc through the real security chain, with tokens
 * verified against the fake identity-service's JWKS. The buyer's token is a
 * real one here, and identity-service's account answer expects exactly it.
 */
@AutoConfigureMockMvc
public abstract class WebTestBase extends SagaTestBase {

    protected static final String BUYER_JWT = TestTokens.buyer(BUYER);

    @Autowired
    protected MockMvc mvc;

    /** The buyer's real token: identity-service's account answer expects exactly it. */
    @Override
    protected String buyerToken() {
        return BUYER_JWT;
    }

    protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** POST /orders/checkout as the buyer, with this key and body. */
    protected ResultActions postCheckout(String token, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = bearer(MockMvcRequestBuilders.post("/orders/checkout"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** The standard order's body: product 10 x 1 at 2.50, product 20 x 2 at 1.25. */
    protected static String standardBody(String card) {
        return "{\"lines\":[{\"productId\":10,\"quantity\":1},{\"productId\":20,\"quantity\":2}],"
                + "\"cardNumber\":\"" + card + "\",\"expectedTotal\":5.00}";
    }

    /** The standard order's products and merchants, and the buyer. */
    protected void givenTheStandardCart() {
        givenBuyer();
        givenProduct(PRODUCT_A, MERCHANT, "2.50");
        givenProduct(PRODUCT_B, OTHER_MERCHANT, "1.25");
        givenMerchant(MERCHANT, "5.00");
        givenMerchant(OTHER_MERCHANT, "5.00");
    }
}
