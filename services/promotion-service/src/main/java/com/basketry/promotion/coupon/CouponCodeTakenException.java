package com.basketry.promotion.coupon;

/** Another coupon already has the normalised code (one namespace for both scopes): a 409. Never quotes the code. */
public class CouponCodeTakenException extends RuntimeException {

    public static final String CODE = "COUPON_CODE_TAKEN";

    public CouponCodeTakenException() {
        super("The coupon code is taken.");
    }
}
