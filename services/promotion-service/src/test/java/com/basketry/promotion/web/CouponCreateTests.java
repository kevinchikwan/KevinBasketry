package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.CouponApiTestBase;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;

/**
 * POST /coupons and GET /coupons/{id} when they succeed (ROADMAP A7): who
 * may create which scope, what is stored, and the answer.
 */
class CouponCreateTests extends CouponApiTestBase {

    private static String answer(long id, String code, String scope, Long merchantId, String type, String value,
                                 String expiresAt, boolean active, boolean expired) {
        return "{\"id\":" + id + ",\"code\":\"" + code + "\",\"scope\":\"" + scope + "\",\"merchantId\":" + merchantId
                + ",\"discountType\":\"" + type + "\",\"discountValue\":" + value + ",\"expiresAt\":\"" + expiresAt
                + "\",\"active\":" + active + ",\"expired\":" + expired + "}";
    }

    private long created(MvcResult result) throws Exception {
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/coupons/" + id);
        return id;
    }

    /**
     * A merchant's coupon is its own, the merchant id taken from the token.
     * Mutations: the merchant id not taken from the token (a 500);
     * the answer without Location.
     */
    @Test
    void aMerchantCreatesItsOwnCouponWithTheMerchantIdFromItsToken() throws Exception {
        MvcResult result = create(MERCHANT_TOKEN, merchantBody("SAVE10")).andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON)).andReturn();
        long id = created(result);

        assertThat(result.getResponse().getContentAsString()).isEqualTo(answer(id, "SAVE10", "MERCHANT", MERCHANT,
                "PERCENTAGE", "10.00", "2027-06-01T00:00:00Z", true, false));
        Coupon saved = coupons.findById(id).orElseThrow();
        assertThat(saved.getScope()).isEqualTo(CouponScope.MERCHANT);
        assertThat(saved.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(saved.isActive()).isTrue();
    }

    /** The token's own merchant id may be sent too. Mutation: any merchant id from a merchant is a 403. */
    @Test
    void aMerchantMaySendItsOwnMerchantId() throws Exception {
        long id = created(create(MERCHANT_TOKEN, merchantBody("SAVE10").replace("{", "{\"merchantId\":" + MERCHANT + ","))
                .andExpect(status().isCreated()).andReturn());
        assertThat(coupons.findById(id).orElseThrow().getMerchantId()).isEqualTo(MERCHANT);
    }

    /** Admin creates both scopes. Mutation: Admin treated as a merchant with no merchant id. */
    @Test
    void adminCreatesAPlatformCouponAndAMerchantsCoupon() throws Exception {
        MvcResult platform = create(ADMIN_TOKEN, platformBody("PLAT5")).andExpect(status().isCreated()).andReturn();
        long platformId = created(platform);
        assertThat(platform.getResponse().getContentAsString()).isEqualTo(answer(platformId, "PLAT5", "PLATFORM",
                null, "FIXED", "5.00", "2027-06-01T00:00:00Z", true, false));

        MvcResult merchant = create(ADMIN_TOKEN, merchantBody("FOR5").replace("{",
                "{\"merchantId\":" + OTHER_MERCHANT + ",")).andExpect(status().isCreated()).andReturn();
        long merchantId = created(merchant);
        assertThat(coupons.findById(merchantId).orElseThrow().getMerchantId()).isEqualTo(OTHER_MERCHANT);
    }

    /** Stored and answered normalised, as pricing looks it up. Mutation: the code stripped but not normalised. */
    @Test
    void theCodeIsNormalised() throws Exception {
        long id = created(create(ADMIN_TOKEN, platformBody(" save-10_x\\t")).andExpect(status().isCreated())
                .andReturn());
        assertThat(coupons.findById(id).orElseThrow().getCode()).isEqualTo(new CouponCode("SAVE-10_X"));
        assertThat(coupons.findByCode(new CouponCode("SAVE-10_X"))).isPresent();
    }

    /** At scale 2 in the answer, as pricing: 10 is 10.00, 7.5 is 7.50. Mutation: the discount without its scale. */
    @Test
    void theDiscountIsAnsweredAtScale2() throws Exception {
        create(ADMIN_TOKEN, platformBody("FIX10").replace("5.00", "10")).andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"discountValue\":10.00,")));
        create(ADMIN_TOKEN, platformBody("FIX75").replace("5.00", "7.5")).andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"discountValue\":7.50,")));
        long full = created(create(ADMIN_TOKEN, platformBody("ALL100").replace("FIXED", "PERCENTAGE")
                .replace("5.00", "100")).andExpect(status().isCreated()).andReturn());
        assertThat(coupons.findById(full).orElseThrow().getDiscount().type()).isEqualTo(DiscountType.PERCENTAGE);
        assertThat(coupons.findById(full).orElseThrow().getDiscount().value()).isEqualTo(new BigDecimal("100.00"));
    }

    /**
     * The expiry as an instant in UTC, to microseconds (what both databases
     * keep), from just after now to exactly 2 years ahead. Mutation: the
     * expiry kept to the second.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2027-01-01T00:00:00.000001Z", "2029-01-01T00:00:00Z", "2029-01-01T02:00:00+02:00",
            "2027-06-01T02:00:00.1234567+02:00"})
    void anExpiryFromJustAfterNowToTwoYearsAheadIsKept(String expiresAt) throws Exception {
        long id = created(create(ADMIN_TOKEN, platformBody("EXP" + Math.abs(expiresAt.hashCode() % 1000))
                .replace("2027-06-01T00:00:00Z", expiresAt)).andExpect(status().isCreated()).andReturn());
        Instant expected = switch (expiresAt) {
            case "2027-01-01T00:00:00.000001Z" -> Instant.parse("2027-01-01T00:00:00.000001Z");
            case "2027-06-01T02:00:00.1234567+02:00" -> Instant.parse("2027-06-01T00:00:00.123456Z");
            default -> Instant.parse("2029-01-01T00:00:00Z");
        };
        assertThat(coupons.findById(id).orElseThrow().getExpiresAt()).isEqualTo(expected);
        read(ADMIN_TOKEN, Long.toString(id)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"expiresAt\":\"" + expected + "\"")));
    }

    /** One nanosecond after now is now once kept to microseconds: refused. Mutation: compared before truncating. */
    @Test
    void anExpiryThatTruncatesToNowIs400() throws Exception {
        create(ADMIN_TOKEN, platformBody("NANO1").replace("2027-06-01T00:00:00Z", "2027-01-01T00:00:00.000000999Z"))
                .andExpect(status().isBadRequest());
    }

    /** A merchant reads its own coupon. Mutation: a merchant's own coupon refused. */
    @Test
    void aMerchantReadsItsOwnCoupon() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, Instant.parse("2027-06-01T00:00:00Z"));
        read(MERCHANT_TOKEN, own.getId().toString()).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(answer(own.getId(), "OWN10", "MERCHANT", MERCHANT, "PERCENTAGE", "10.00",
                        "2027-06-01T00:00:00Z", true, false), JsonCompareMode.STRICT));
    }

    /** Admin reads every coupon. Mutation: Admin limited to platform coupons. */
    @Test
    void adminReadsAnyCoupon() throws Exception {
        Coupon merchant = saveMerchantCoupon("OWN10", MERCHANT, Instant.parse("2027-06-01T00:00:00Z"));
        Coupon platform = savePlatformCoupon("PLAT10", Instant.parse("2027-06-01T00:00:00Z"));
        read(ADMIN_TOKEN, merchant.getId().toString()).andExpect(status().isOk());
        read(ADMIN_TOKEN, platform.getId().toString()).andExpect(status().isOk())
                .andExpect(content().json(answer(platform.getId(), "PLAT10", "PLATFORM", null, "FIXED", "5.00",
                        "2027-06-01T00:00:00Z", true, false), JsonCompareMode.STRICT));
    }

    /**
     * Expired is read against the service clock, the end exclusive; active
     * stays what is stored. Mutations: expired as "now > expiresAt"; expired
     * read from the system clock.
     */
    @Test
    void anExpiredCouponIsActiveAndExpired() throws Exception {
        Coupon own = saveMerchantCoupon("OWN10", MERCHANT, Instant.parse("2027-06-01T00:00:00Z"));
        clock.set(Instant.parse("2027-06-01T00:00:00Z"));
        read(MERCHANT_TOKEN, own.getId().toString()).andExpect(status().isOk())
                .andExpect(content().json(answer(own.getId(), "OWN10", "MERCHANT", MERCHANT, "PERCENTAGE", "10.00",
                        "2027-06-01T00:00:00Z", true, true), JsonCompareMode.STRICT));
        clock.set(Instant.parse("2027-05-31T23:59:59.999999Z"));
        read(MERCHANT_TOKEN, own.getId().toString())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"expired\":false")));
    }

    /** The answer's header for a front-end. Mutation: Location pointing at another id. */
    @Test
    void theLocationHeaderPointsAtTheCoupon() throws Exception {
        MvcResult result = create(MERCHANT_TOKEN, merchantBody("LOC10")).andExpect(status().isCreated())
                .andExpect(header().exists("Location")).andReturn();
        read(MERCHANT_TOKEN, Long.toString(created(result))).andExpect(status().isOk());
    }
}
