package com.basketry.promotion.web;

import java.util.List;

/** A coupon request that breaks the endpoint's rules: 400 naming every broken rule, never a value sent. */
class InvalidCouponRequestException extends RuntimeException {

    private final List<String> violations;

    InvalidCouponRequestException(List<String> violations) {
        super("The coupon request breaks " + violations.size() + " rule(s).");
        this.violations = List.copyOf(violations);
    }

    List<String> violations() {
        return violations;
    }
}
