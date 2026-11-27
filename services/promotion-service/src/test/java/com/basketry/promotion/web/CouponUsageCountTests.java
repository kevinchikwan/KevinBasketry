package com.basketry.promotion.web;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.support.CouponApiTestBase;
import com.basketry.promotion.usage.CouponUsage;
import com.basketry.promotion.usage.CouponUsageRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every coupon answer carries usageCount (ROADMAP A8, A7's Q13): the number
 * of orders counted for the coupon from order.succeeded. It is shown to the
 * coupon's owner and Admin, and never affects pricing. A list page counts its
 * coupons in one grouped query, never one query per coupon.
 */
class CouponUsageCountTests extends CouponApiTestBase {

    private static final Instant JUNE = Instant.parse("2027-06-01T00:00:00Z");

    @MockitoSpyBean
    private CouponUsageRepository usages;

    private void used(Coupon coupon, int times) {
        for (int i = 0; i < times; i++) {
            usages.save(new CouponUsage(UUID.randomUUID(), coupon.getId(), 100 + i, NOW));
        }
    }

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(get("/coupons" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions deactivate(String token, long id) throws Exception {
        return mvc.perform(post("/coupons/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** Mutations: read answering 0 always; the count of another coupon (the first one's id). */
    @Test
    void aCouponIsAnsweredWithItsUsageCount() throws Exception {
        Coupon used = saveMerchantCoupon("USED10", MERCHANT, JUNE);
        Coupon other = saveMerchantCoupon("OTHER10", MERCHANT, JUNE);
        used(used, 2);
        used(other, 1);

        read(MERCHANT_TOKEN, Long.toString(used.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.usageCount").value(2));
        read(ADMIN_TOKEN, Long.toString(other.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.usageCount").value(1));
    }

    /** A new coupon has no usage. Mutation: create answering the coupon id as the count. */
    @Test
    void aNewCouponHasAUsageCountOfZero() throws Exception {
        create(MERCHANT_TOKEN, merchantBody("NEW10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.usageCount").value(0));
    }

    /** Deactivating keeps the count. Mutation: deactivate answering 0 always. */
    @Test
    void aDeactivatedCouponKeepsItsUsageCount() throws Exception {
        Coupon used = saveMerchantCoupon("USED10", MERCHANT, JUNE);
        used(used, 3);

        deactivate(MERCHANT_TOKEN, used.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.usageCount").value(3));
    }

    /**
     * Each coupon of a page with its own count, 0 for one never used, in one
     * grouped query for the page. Mutations: one countByCouponId per coupon
     * (the grouped query never runs); every coupon of the page given the
     * page's total; a coupon missing from the grouped result read with get (the
     * null unboxed: a 500).
     */
    @Test
    void aPageCountsItsCouponsInOneGroupedQuery() throws Exception {
        Coupon a = saveMerchantCoupon("AAA10", MERCHANT, JUNE);
        Coupon b = saveMerchantCoupon("BBB10", MERCHANT, JUNE);
        Coupon c = savePlatformCoupon("CCC10", JUNE);
        used(a, 2);
        used(c, 1);

        list(ADMIN_TOKEN, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(a.getId()))
                .andExpect(jsonPath("$.content[0].usageCount").value(2))
                .andExpect(jsonPath("$.content[1].id").value(b.getId()))
                .andExpect(jsonPath("$.content[1].usageCount").value(0))
                .andExpect(jsonPath("$.content[2].id").value(c.getId()))
                .andExpect(jsonPath("$.content[2].usageCount").value(1));

        verify(usages, times(1)).countByCouponIds(anyCollection());
        verify(usages, never()).countByCouponId(anyLong());
    }

    /** A page past the end makes no count query. Mutation: the grouped query run for an empty page. */
    @Test
    void anEmptyPageMakesNoCountQuery() throws Exception {
        Coupon used = saveMerchantCoupon("USED10", MERCHANT, JUNE);
        used(used, 1);

        list(MERCHANT_TOKEN, "?page=5").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(usages, never()).countByCouponIds(anyCollection());
    }
}
