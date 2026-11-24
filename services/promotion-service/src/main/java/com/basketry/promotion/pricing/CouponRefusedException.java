package com.basketry.promotion.pricing;

/**
 * A coupon that cannot be priced, with the reason (the pricing endpoint's 409).
 * The message names the reason and never quotes the code.
 */
public class CouponRefusedException extends RuntimeException {

    private final CouponRefusal reason;

    public CouponRefusedException(CouponRefusal reason) {
        super("Coupon refused: " + reason.name());
        this.reason = reason;
    }

    public CouponRefusal reason() {
        return reason;
    }
}
