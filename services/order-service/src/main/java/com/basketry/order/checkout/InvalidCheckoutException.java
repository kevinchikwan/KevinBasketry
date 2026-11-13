package com.basketry.order.checkout;

import java.util.List;

/**
 * 400: the checkout breaks a body rule; {@link #violations()} names every one,
 * never quoting a value. {@link #couponsNotSupported()} is true when a coupon
 * was given, so the answer can carry the code COUPONS_NOT_SUPPORTED.
 */
public class InvalidCheckoutException extends RuntimeException {

    private final List<String> violations;
    private final boolean couponsNotSupported;

    InvalidCheckoutException(List<String> violations, boolean couponsNotSupported) {
        super("The checkout breaks " + violations.size() + " rule(s): " + String.join(" ", violations));
        this.violations = List.copyOf(violations);
        this.couponsNotSupported = couponsNotSupported;
    }

    public List<String> violations() {
        return violations;
    }

    public boolean couponsNotSupported() {
        return couponsNotSupported;
    }
}
