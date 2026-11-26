package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.support.CouponApiTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /coupons when it succeeds (ROADMAP A7): a merchant sees its own merchant
 * coupons, Admin every coupon; paging as GET /accounts, in id order.
 */
class CouponListTests extends CouponApiTestBase {

    private static final Instant JUNE = Instant.parse("2027-06-01T00:00:00Z");

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(get("/coupons" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * Its own merchant coupons only: never another merchant's, never a
     * platform coupon. Mutation: a merchant sees every coupon.
     */
    @Test
    void aMerchantSeesOnlyItsOwnCoupons() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        saveMerchantCoupon("OTHER10", OTHER_MERCHANT, JUNE);
        savePlatformCoupon("PLAT10", JUNE);
        Coupon ownToo = saveMerchantCoupon("OWN20", MERCHANT, JUNE);

        list(MERCHANT_TOKEN, "").andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(
                        own.getId().intValue(), ownToo.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** Admin sees every coupon, both scopes. Mutation: Admin given a merchant's list. */
    @Test
    void adminSeesEveryCoupon() throws Exception {
        saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        saveMerchantCoupon("OTHER10", OTHER_MERCHANT, JUNE);
        savePlatformCoupon("PLAT10", JUNE);

        list(ADMIN_TOKEN, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(org.hamcrest.Matchers.contains(
                        "OWN10", "OTHER10", "PLAT10")))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /**
     * The whole page, body for body: each item is the coupon as GET
     * /coupons/{id} answers it, expired read with the service clock.
     * Mutation: an item's expired read from the system clock.
     */
    @Test
    void thePageHoldsEachCouponAsItIsReadAlone() throws Exception {
        Coupon june = saveMerchantCoupon("OWN10", MERCHANT, JUNE);
        Coupon past = saveMerchantCoupon("OLD10", MERCHANT, Instant.parse("2027-01-01T00:00:00Z"));

        list(MERCHANT_TOKEN, "").andExpect(status().isOk()).andExpect(content().json("{\"content\":["
                + "{\"id\":" + june.getId() + ",\"code\":\"OWN10\",\"scope\":\"MERCHANT\",\"merchantId\":3,"
                + "\"discountType\":\"PERCENTAGE\",\"discountValue\":10.00,\"expiresAt\":\"2027-06-01T00:00:00Z\","
                + "\"active\":true,\"expired\":false},"
                + "{\"id\":" + past.getId() + ",\"code\":\"OLD10\",\"scope\":\"MERCHANT\",\"merchantId\":3,"
                + "\"discountType\":\"PERCENTAGE\",\"discountValue\":10.00,\"expiresAt\":\"2027-01-01T00:00:00Z\","
                + "\"active\":true,\"expired\":true}],"
                + "\"page\":0,\"size\":20,\"totalElements\":2,\"totalPages\":1}", JsonCompareMode.STRICT));
    }

    /**
     * page from 0, size 1 to 100 (20 by default), in id order; a page past
     * the end is empty, not an error. Mutations: sorted by code instead of
     * id; the default size 10; totalPages one too many.
     */
    @Test
    void pagesAreInIdOrder() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String code : List.of("ZZZ5", "AAA5", "MMM5", "BBB5", "YYY5")) {
            ids.add(savePlatformCoupon(code, JUNE).getId());
        }

        list(ADMIN_TOKEN, "?page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(
                        ids.get(2).intValue(), ids.get(3).intValue())))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        list(ADMIN_TOKEN, "?page=9&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(5));
        list(ADMIN_TOKEN, "").andExpect(jsonPath("$.size").value(20));
        list(ADMIN_TOKEN, "?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
        assertThat(ids).isSorted();
    }
}
