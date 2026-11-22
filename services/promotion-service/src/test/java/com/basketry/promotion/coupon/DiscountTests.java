package com.basketry.promotion.coupon;

import static com.basketry.promotion.coupon.DiscountType.FIXED;
import static com.basketry.promotion.coupon.DiscountType.PERCENTAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The discount rules (DESIGN.md Section 2, promotion-service, amount rules). */
class DiscountTests {

    /** Mutations: {@code compareTo(MAX_PERCENTAGE) < 0} (100.00 refused); {@code compareTo(MAX_FIXED) < 0}. */
    @Test
    void theEdgesAreAccepted() {
        assertThat(discount(PERCENTAGE, "0.01").value()).isEqualTo(new BigDecimal("0.01"));
        assertThat(discount(PERCENTAGE, "100.00").value()).isEqualTo(new BigDecimal("100.00"));
        assertThat(discount(FIXED, "0.01").value()).isEqualTo(new BigDecimal("0.01"));
        assertThat(discount(FIXED, "9999999999.99").value()).isEqualTo(new BigDecimal("9999999999.99"));
    }

    /** Mutations: the lower bound {@code signum() >= 0} instead of 0.01; the upper bound dropped. */
    @Test
    void aPercentageIsFromOneHundredthToOneHundred() {
        assertRefused(PERCENTAGE, "0.00");
        assertRefused(PERCENTAGE, "100.01");
    }

    /** Mutation: {@code signum() >= 0} for FIXED. */
    @Test
    void aFixedDiscountIsAboveZero() {
        assertRefused(FIXED, "0.00");
        assertRefused(FIXED, "-1.00");
    }

    /** Mutation: the maximum check dropped (the column would then fail with an overflow, not a refusal). */
    @Test
    void aFixedDiscountIsAtMostPaymentServicesMaximum() {
        assertRefused(FIXED, "10000000000.00");
    }

    /**
     * The NUMERIC(12, 2) column would store 0.005 as 0.01 (CouponTableConstraintTests),
     * so this is the only refusal. Mutations: the scale check dropped (an
     * ArithmeticException from setScale instead); {@code value.scale() > 2}
     * without stripTrailingZeros (10.50000 refused).
     */
    @Test
    void aThirdDecimalPlaceIsRefusedButTrailingZerosAreNot() {
        assertRefused(FIXED, "0.005");
        assertRefused(PERCENTAGE, "10.125");
        assertThat(discount(PERCENTAGE, "10.50000").value()).isEqualTo(new BigDecimal("10.50"));
    }

    /** BigDecimal.equals compares the scale. Mutation: the setScale(2) at the end dropped. */
    @Test
    void theValueIsAtScaleTwo() {
        assertThat(discount(FIXED, "5").value()).isEqualTo(new BigDecimal("5.00"));
    }

    /** Mutation: the null check dropped (a NullPointerException from the switch instead). */
    @Test
    void aTypeAndAValueAreRequired() {
        assertThatThrownBy(() -> new Discount(null, BigDecimal.TEN)).isExactlyInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> new Discount(FIXED, null)).isExactlyInstanceOf(InvalidCouponException.class);
    }

    private static Discount discount(DiscountType type, String value) {
        return new Discount(type, new BigDecimal(value));
    }

    private static void assertRefused(DiscountType type, String value) {
        assertThatThrownBy(() -> discount(type, value)).isExactlyInstanceOf(InvalidCouponException.class);
    }
}
