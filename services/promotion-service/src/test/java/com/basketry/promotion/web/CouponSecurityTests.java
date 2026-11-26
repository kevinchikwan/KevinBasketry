package com.basketry.promotion.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.CouponApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The coupon chain keeps to /coupons (DESIGN.md Section 3, promotion-service):
 * a bearer token opens nothing else, the internal key opens no coupon
 * endpoint, and CORS is for the coupon endpoints only.
 */
class CouponSecurityTests extends CouponApiTestBase {

    /** Mutation: the coupon chain matching /internal/** before the internal chain. */
    @Test
    void aTokenIsNotTheInternalKey() throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body("\"SAVE10\"", "3", "25.50")))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    /** Mutation: the coupon chain matching every path. */
    @Test
    void aTokenOpensNoOtherPath() throws Exception {
        mvc.perform(get("/other").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    /** The internal key opens no coupon endpoint. Mutation: the internal chain matching /coupons. */
    @Test
    void theInternalKeyIsNotAToken() throws Exception {
        mvc.perform(get("/coupons/1").header("X-Internal-Api-Key", API_KEY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A browser on the portal's origin may call the coupon endpoints and read
     * Location; another origin gets no CORS headers. Mutations: CORS disabled
     * on the coupon chain; Location not exposed.
     */
    @Test
    void corsAllowsThePortalOriginOnly() throws Exception {
        mvc.perform(options("/coupons").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(get("/coupons/999").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location"));
        mvc.perform(options("/coupons").header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
