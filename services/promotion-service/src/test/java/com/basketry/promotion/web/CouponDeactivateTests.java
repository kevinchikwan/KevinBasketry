package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.support.CouponApiTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /coupons/{id}/deactivate when it succeeds (FR-M11, FR-A10; ROADMAP
 * A7): one way, never a delete; the coupon is answered as it now is, and
 * pricing refuses it from then on.
 */
class CouponDeactivateTests extends CouponApiTestBase {

    private static final Instant JUNE = Instant.parse("2027-06-01T00:00:00Z");

    private ResultActions deactivate(String token, long id) throws Exception {
        return mvc.perform(post("/coupons/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * A merchant deactivates its own coupon: 200 with the coupon, now
     * inactive, and stored so. Mutation: the change not saved.
     */
    @Test
    void aMerchantDeactivatesItsOwnCoupon() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);

        deactivate(MERCHANT_TOKEN, own.getId()).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"id\":" + own.getId() + ",\"code\":\"OWN10\",\"scope\":\"MERCHANT\","
                        + "\"merchantId\":3,\"discountType\":\"PERCENTAGE\",\"discountValue\":10.00,"
                        + "\"expiresAt\":\"2027-06-01T00:00:00Z\",\"active\":false,\"expired\":false}",
                        JsonCompareMode.STRICT));
        assertThat(coupons.findById(own.getId()).orElseThrow().isActive()).isFalse();
    }

    /** Admin deactivates any coupon, both scopes. Mutation: Admin limited to platform coupons. */
    @Test
    void adminDeactivatesAnyCoupon() throws Exception {
        Coupon merchant = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        Coupon platform = savePlatformCoupon("PLAT10", JUNE);

        deactivate(ADMIN_TOKEN, merchant.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        deactivate(ADMIN_TOKEN, platform.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(coupons.findAll()).noneMatch(Coupon::isActive);
    }

    /**
     * Again is the same 200, nothing changed; an expired coupon can be
     * deactivated too. Mutation: an inactive coupon refused with a 409.
     */
    @Test
    void deactivatingTwiceOrAnExpiredCouponIsA200() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        Coupon past = saveMerchantCoupon("OLD10", MERCHANT, Instant.parse("2026-12-31T00:00:00Z"));

        deactivate(MERCHANT_TOKEN, own.getId()).andExpect(status().isOk());
        deactivate(MERCHANT_TOKEN, own.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        deactivate(MERCHANT_TOKEN, past.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.expired").value(true));
    }

    /**
     * Never a delete: the row stays, and pricing refuses the code as
     * COUPON_INACTIVE (not COUPON_NOT_FOUND). Mutation: the coupon deleted.
     */
    @Test
    void aDeactivatedCouponStaysAndPricingRefusesIt() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        deactivate(MERCHANT_TOKEN, own.getId()).andExpect(status().isOk());

        assertThat(coupons.findById(own.getId())).isPresent();
        price(body("\"OWN10\"", "3", "25.50")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_INACTIVE"));
    }

    /** A body is ignored, whatever it is. Mutation: the deactivation accepting only a JSON body. */
    @Test
    void aBodyIsIgnored() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        mvc.perform(post("/coupons/" + own.getId() + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MERCHANT_TOKEN)
                        .contentType(MediaType.TEXT_PLAIN).content("{\"active\":true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }
}
