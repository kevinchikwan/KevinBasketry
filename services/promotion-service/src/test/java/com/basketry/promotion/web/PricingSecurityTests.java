package com.basketry.promotion.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.config.InternalApiKey;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.WebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The /internal/** chain (DESIGN.md Section 3, promotion-service), the tests
 * of merchant-service's chain: the key in its own header only, the same bare
 * 401 for no key and a wrong key, no CORS; and the default chain: health open,
 * any other path a bare 401.
 */
class PricingSecurityTests extends WebTestBase {

    private static final String BODY = body("\"SAVE10\"", "3", "25.50");

    @BeforeEach
    void aCoupon() {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");
    }

    private static MockHttpServletRequestBuilder pricing() {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    /** Mutation: {@code permitAll()} in the internal chain. */
    @Test
    void noKeyIsABare401() throws Exception {
        mvc.perform(pricing())
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    /** Mutation: {@code matches} true for any non-null key. */
    @Test
    void aWrongKeyIsABare401() throws Exception {
        mvc.perform(pricing().header(InternalApiKey.HEADER, API_KEY + "x"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(pricing().header(InternalApiKey.HEADER, API_KEY.substring(1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(pricing().header(InternalApiKey.HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    /** The key counts only in its own header. Mutation: the filter also reads a bearer token as the key. */
    @Test
    void theKeyAsABearerTokenIs401() throws Exception {
        mvc.perform(pricing().header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    /** Mutation: the filter never sets the authentication. */
    @Test
    void theRightKeyIsPriced() throws Exception {
        mvc.perform(pricing().header(InternalApiKey.HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants[0].discount").value(2.55));
    }

    /**
     * No CORS headers from /internal/**, for a request with the key or a
     * preflight. Mutation: a permissive CORS source in the internal chain.
     */
    @Test
    void noCorsHeadersFromInternal() throws Exception {
        mvc.perform(pricing().header(InternalApiKey.HEADER, API_KEY).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /**
     * Health (GET and HEAD, and its probe groups) is open; HEAD on the internal
     * path and any other path are a bare 401. Mutations: {@code anyRequest().permitAll()}
     * in the default chain (404 instead); health not permitted (401).
     */
    @Test
    void healthIsOpenAndEveryOtherPathIsABare401() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(head("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        mvc.perform(head(PATH)).andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/coupons")).andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(post("/anything")).andExpect(status().isUnauthorized());
    }
}
