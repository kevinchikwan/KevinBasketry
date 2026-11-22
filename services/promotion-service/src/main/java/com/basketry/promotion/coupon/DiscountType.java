package com.basketry.promotion.coupon;

/** How a coupon's value is read (DESIGN.md Section 2, promotion-service). */
public enum DiscountType {
    /** A percentage of each eligible merchant's subtotal, 0.01 to 100.00. */
    PERCENTAGE,
    /** An amount in GBP, above 0, at most 9,999,999,999.99, capped and split. */
    FIXED
}
