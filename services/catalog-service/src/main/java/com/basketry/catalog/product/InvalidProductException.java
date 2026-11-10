package com.basketry.catalog.product;

import java.util.List;

/** A product field breaks a rule of {@link ProductLimits}: a 400 once the REST API exists. */
public class InvalidProductException extends RuntimeException {

    private final List<String> violations;

    public InvalidProductException(List<String> violations) {
        super(String.join(" ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
