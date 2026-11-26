package com.basketry.promotion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The coupon endpoints (ROADMAP A7) through MockMvc, with bearer tokens and
 * the service clock fixed at {@link #NOW}, so the expiry rules are exact.
 */
@Import(CouponApiTestBase.FixedClock.class)
public abstract class CouponApiTestBase extends WebTestBase {

    public static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    /** The merchant of {@link #MERCHANT_TOKEN}, and another one. */
    public static final long MERCHANT = 3;
    public static final long OTHER_MERCHANT = 5;

    public static final String ADMIN_TOKEN = TestTokens.admin();
    public static final String MERCHANT_TOKEN = TestTokens.merchant(21, MERCHANT);
    public static final String OTHER_MERCHANT_TOKEN = TestTokens.merchant(22, OTHER_MERCHANT);
    public static final String BUYER_TOKEN = TestTokens.buyer(23);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        MutableClock couponApiClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void theClockAtNow() {
        clock.set(NOW);
    }

    protected ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions read(String token, String id) throws Exception {
        return mvc.perform(get("/coupons/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** A merchant coupon body: 10.00 percent, expiring on 2027-06-01. */
    protected static String merchantBody(String code) {
        return "{\"code\":\"" + code + "\",\"scope\":\"MERCHANT\",\"discountType\":\"PERCENTAGE\","
                + "\"discountValue\":10.00,\"expiresAt\":\"2027-06-01T00:00:00Z\"}";
    }

    /** A platform coupon body: 5.00 off, expiring on 2027-06-01. */
    protected static String platformBody(String code) {
        return "{\"code\":\"" + code + "\",\"scope\":\"PLATFORM\",\"discountType\":\"FIXED\","
                + "\"discountValue\":5.00,\"expiresAt\":\"2027-06-01T00:00:00Z\"}";
    }

    protected Coupon saveMerchantCoupon(String code, long owner, Instant expiresAt) {
        return coupons.save(new Coupon(new CouponCode(code), CouponScope.MERCHANT, owner,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), expiresAt));
    }

    protected Coupon savePlatformCoupon(String code, Instant expiresAt) {
        return coupons.save(new Coupon(new CouponCode(code), CouponScope.PLATFORM, null,
                new Discount(DiscountType.FIXED, new BigDecimal("5")), expiresAt));
    }
}
