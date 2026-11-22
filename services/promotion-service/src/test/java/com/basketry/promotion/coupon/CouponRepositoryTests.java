package com.basketry.promotion.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Coupons through JPA, on H2 and on PostgreSQL. No test transaction: each
 * repository call is its own, so a read really comes back from the database.
 */
class CouponRepositoryTests extends PromotionServiceTestBase {

    // Microseconds: what TIMESTAMP WITH TIME ZONE keeps on both databases.
    static final Instant EXPIRY = Instant.parse("2027-03-01T12:34:56.123456Z");

    @Autowired
    private CouponRepository coupons;

    /** Mutation: {@code @Enumerated(EnumType.ORDINAL)} on scope (the column holds text, not a number). */
    @Test
    void aCouponComesBackAsItWasSaved() {
        Discount discount = new Discount(DiscountType.FIXED, new BigDecimal("12.50"));
        long id = coupons.save(new Coupon(new CouponCode("M5-OFF"), CouponScope.MERCHANT, 5L, discount, EXPIRY))
                .getId();

        Coupon read = coupons.findById(id).orElseThrow();

        assertThat(read.getCode()).isEqualTo(new CouponCode("M5-OFF"));
        assertThat(read.getScope()).isEqualTo(CouponScope.MERCHANT);
        assertThat(read.getMerchantId()).isEqualTo(5L);
        assertThat(read.getDiscount()).isEqualTo(discount);
        assertThat(read.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(read.isActive()).isTrue();
    }

    /**
     * Whatever case a person typed, the lookup is by the normalised code.
     * Mutation: the converter stores {@code code.value().toLowerCase(Locale.ROOT)}
     * (ck_coupon_code refuses it).
     */
    @Test
    void aCouponIsFoundByItsNormalisedCode() {
        coupons.save(new Coupon(new CouponCode("SAVE10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), EXPIRY));

        assertThat(coupons.findByCode(CouponCode.normalise(" save10 "))).isPresent();
        assertThat(coupons.findByCode(new CouponCode("SAVE11"))).isEmpty();
    }

    /** Mutation: {@code @Column(updatable = false)} on active (the change is never written). */
    @Test
    void aDeactivationIsStored() {
        Coupon coupon = coupons.save(new Coupon(new CouponCode("SAVE10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), EXPIRY));

        coupon.deactivate();
        coupons.save(coupon);

        assertThat(coupons.findById(coupon.getId()).orElseThrow().isActive()).isFalse();
    }
}
