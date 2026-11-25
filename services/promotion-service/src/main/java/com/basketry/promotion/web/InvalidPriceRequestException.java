package com.basketry.promotion.web;

import java.util.List;

/** A pricing body that breaks the endpoint's rules: 400 naming every broken rule, never a value sent. */
class InvalidPriceRequestException extends RuntimeException {

    private final List<String> violations;

    InvalidPriceRequestException(List<String> violations) {
        super("The pricing request breaks " + violations.size() + " rule(s).");
        this.violations = List.copyOf(violations);
    }

    List<String> violations() {
        return violations;
    }
}
