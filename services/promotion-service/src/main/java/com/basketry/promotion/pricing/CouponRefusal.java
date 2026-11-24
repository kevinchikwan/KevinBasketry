package com.basketry.promotion.pricing;

/**
 * Why a coupon is refused at pricing (DESIGN.md Section 2, promotion-service,
 * Refusals), named as the 409's {@code code}. Declared in the order the checks
 * run: the first that applies is the answer.
 */
public enum CouponRefusal {
    /** No coupon has the normalised code. */
    COUPON_NOT_FOUND,
    /** The coupon was deactivated. */
    COUPON_INACTIVE,
    /** Now is at or after the coupon's expiresAt. */
    COUPON_EXPIRED,
    /** A merchant coupon whose merchant is not in the cart. */
    COUPON_NOT_APPLICABLE
}
