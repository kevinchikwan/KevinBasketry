package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.support.CouponApiTestBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every answer of POST /coupons/{id}/deactivate other than a success (ROADMAP
 * A7), body for body: 401, 403, 400, 404 (Admin only) and 405. Nothing is
 * changed by any of them.
 */
class CouponDeactivateAnswersTests extends CouponApiTestBase {

    private static final Instant JUNE = Instant.parse("2027-06-01T00:00:00Z");

    private ResultActions deactivate(String token, String id) throws Exception {
        return mvc.perform(post("/coupons/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** Mutation: the deactivation permitted without a token in the chain. */
    @Test
    void noTokenIs401() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        mvc.perform(post("/coupons/" + own.getId() + "/deactivate"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        assertThat(coupons.findById(own.getId()).orElseThrow().isActive()).isTrue();
    }

    /** Before the id is read. Mutation: the capability check left out. */
    @Test
    void aBuyerIs403() throws Exception {
        CouponCreateAnswersTests.expect(deactivate(BUYER_TOKEN, "abc"), 403, CouponCreateAnswersTests.problem(403,
                "Forbidden", "Only Admin and merchants can manage coupons.", "/coupons/abc/deactivate"));
    }

    /** Mutation: the id rule removed (a 500). */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "99999999999999999999"})
    void anIdThatIsNotAWholeNumberAboveZeroIs400(String id) throws Exception {
        CouponCreateAnswersTests.expect(deactivate(ADMIN_TOKEN, id), 400,
                CouponCreateAnswersTests.invalid("/coupons/" + id + "/deactivate", "id must be a whole number above 0."));
    }

    /** Mutation: Admin's 404 as a 403. */
    @Test
    void aMissingCouponIs404ForAdmin() throws Exception {
        CouponCreateAnswersTests.expect(deactivate(ADMIN_TOKEN, "999"), 404, CouponCreateAnswersTests.problem(404,
                "Not Found", "No coupon has this id.", "/coupons/999/deactivate"));
    }

    /**
     * Another merchant's coupon, a platform coupon and a missing one: the same
     * 403, and nothing changed. Mutations: a merchant may deactivate any
     * coupon; a merchant may deactivate a platform coupon.
     */
    @Test
    void aMerchantGets403ForACouponThatIsNotItsOwn() throws Exception {
        Coupon others = saveMerchantCoupon("OTHER10", OTHER_MERCHANT, JUNE);
        Coupon platform = savePlatformCoupon("PLAT10", JUNE);
        for (long id : List.of(others.getId(), platform.getId(), 999_999L)) {
            CouponCreateAnswersTests.expect(deactivate(MERCHANT_TOKEN, Long.toString(id)), 403,
                    CouponCreateAnswersTests.problem(403, "Forbidden",
                            "Only Admin and the coupon's merchant can deactivate this coupon.",
                            "/coupons/" + id + "/deactivate"));
        }
        assertThat(coupons.findAll()).allMatch(Coupon::isActive);
    }

    /** Only POST. Mutation: the deactivation mapped for any method. */
    @Test
    void anotherMethodIs405() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        mvc.perform(get("/coupons/" + own.getId() + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MERCHANT_TOKEN))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"));
        assertThat(coupons.findById(own.getId()).orElseThrow().isActive()).isTrue();
    }
}
