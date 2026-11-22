package com.basketry.promotion.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A coupon's discount (DESIGN.md Section 2, promotion-service, amount rules),
 * always at scale 2. {@code PERCENTAGE}: 0.01 to 100.00. {@code FIXED}: above 0,
 * at most {@link #MAX_FIXED}. Either way at most 2 decimal places: the
 * NUMERIC(12, 2) column would round a third one before its CHECK runs, so this
 * is the only place that refuses 0.005. The discount maths (ROADMAP A4) takes
 * this type, not the entity.
 */
public record Discount(DiscountType type, BigDecimal value) {

    /** payment-service's maximum amount (ChargeCommand.MAX_AMOUNT), and NUMERIC(12, 2)'s. */
    public static final BigDecimal MAX_FIXED = new BigDecimal("9999999999.99");

    static final BigDecimal MIN_PERCENTAGE = new BigDecimal("0.01");
    static final BigDecimal MAX_PERCENTAGE = new BigDecimal("100.00");

    public Discount {
        if (type == null || value == null) {
            throw new InvalidCouponException("A discount needs a type and a value.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new InvalidCouponException("A discount has at most 2 decimal places.");
        }
        boolean inRange = switch (type) {
            case PERCENTAGE -> value.compareTo(MIN_PERCENTAGE) >= 0 && value.compareTo(MAX_PERCENTAGE) <= 0;
            case FIXED -> value.signum() > 0 && value.compareTo(MAX_FIXED) <= 0;
        };
        if (!inRange) {
            throw new InvalidCouponException(type == DiscountType.PERCENTAGE
                    ? "A percentage discount is 0.01 to 100.00."
                    : "A fixed discount is above 0 and at most " + MAX_FIXED.toPlainString() + ".");
        }
        // Exact: the scale check above means no digit is lost.
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
