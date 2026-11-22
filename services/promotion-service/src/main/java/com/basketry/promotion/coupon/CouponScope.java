package com.basketry.promotion.coupon;

/** Whose coupon it is (DESIGN.md Section 2, promotion-service). */
public enum CouponScope {
    /** A merchant's: applies only to that merchant's part of a cart. */
    MERCHANT,
    /** Admin's: applies to every merchant in a cart. */
    PLATFORM
}
