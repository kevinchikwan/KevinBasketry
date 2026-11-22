package com.basketry.promotion.coupon;

/**
 * A coupon value that breaks a rule of DESIGN.md Section 2, promotion-service:
 * a code, a discount, or a scope and merchant that do not go together. The
 * message names the rule and never quotes the value.
 */
public class InvalidCouponException extends RuntimeException {

    public InvalidCouponException(String message) {
        super(message);
    }
}
