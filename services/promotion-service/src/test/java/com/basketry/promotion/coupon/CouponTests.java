package com.basketry.promotion.coupon;

import static com.basketry.promotion.coupon.CouponScope.MERCHANT;
import static com.basketry.promotion.coupon.CouponScope.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The coupon's own rules, without a database (DESIGN.md Section 2, promotion-service). */
class CouponTests {

    static final CouponCode CODE = new CouponCode("SAVE10");
    static final Discount TEN_PERCENT = new Discount(DiscountType.PERCENTAGE, new BigDecimal("10.00"));
    static final Instant EXPIRY = Instant.parse("2027-01-01T00:00:00Z");

    /** Mutation: {@code this.active = true} dropped from the constructor. */
    @Test
    void aNewCouponIsActiveWithWhatItWasGiven() {
        Coupon merchant = new Coupon(CODE, MERCHANT, 5L, TEN_PERCENT, EXPIRY);
        Coupon platform = new Coupon(CODE, PLATFORM, null, TEN_PERCENT, EXPIRY);

        assertThat(merchant.isActive()).isTrue();
        assertThat(merchant.getMerchantId()).isEqualTo(5L);
        assertThat(merchant.getDiscount()).isEqualTo(TEN_PERCENT);
        assertThat(platform.isActive()).isTrue();
        assertThat(platform.getMerchantId()).isNull();
    }

    /**
     * Mutations: the one-way rule {@code scope != MERCHANT || merchantId != null}
     * (a platform coupon with merchant 5 passes); {@code merchantId >= 0}.
     */
    @Test
    void aMerchantIdIsGivenExactlyForAMerchantCoupon() {
        assertThatThrownBy(() -> new Coupon(CODE, MERCHANT, null, TEN_PERCENT, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Coupon(CODE, MERCHANT, 0L, TEN_PERCENT, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Coupon(CODE, PLATFORM, 5L, TEN_PERCENT, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
    }

    /** Mutation: {@code expiresAt == null} dropped from the null check. */
    @Test
    void everyPartIsRequired() {
        assertThatThrownBy(() -> new Coupon(null, PLATFORM, null, TEN_PERCENT, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Coupon(CODE, null, null, TEN_PERCENT, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Coupon(CODE, PLATFORM, null, null, EXPIRY))
                .isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Coupon(CODE, PLATFORM, null, TEN_PERCENT, null))
                .isInstanceOf(InvalidCouponException.class);
    }

    /** Mutation: deactivate() leaves active as it was. */
    @Test
    void deactivatingTurnsItOff() {
        Coupon coupon = new Coupon(CODE, PLATFORM, null, TEN_PERCENT, EXPIRY);

        coupon.deactivate();

        assertThat(coupon.isActive()).isFalse();
    }
}
