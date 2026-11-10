package com.basketry.catalog.product;

import java.math.BigDecimal;
import java.util.List;

/**
 * The field rules shared by create and update. Each check adds a message to
 * {@code violations} and returns the normalized value: names and categories
 * trimmed, prices at scale 2. More than 2 decimals is refused, never rounded;
 * trailing zeros do not count ({@code 9.990} is 9.99), as merchant-service's
 * {@code @Digits}.
 */
final class ProductFields {

    private ProductFields() {
    }

    static String name(String value, List<String> violations) {
        return requiredText("name", value, ProductLimits.MAX_NAME_LENGTH, violations);
    }

    static String category(String value, List<String> violations) {
        return requiredText("category", value, ProductLimits.MAX_CATEGORY_LENGTH, violations);
    }

    /** Optional: null and blank both mean "no description", stored as ''. */
    static String description(String value, List<String> violations) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() > ProductLimits.MAX_DESCRIPTION_LENGTH) {
            violations.add("description must be at most " + ProductLimits.MAX_DESCRIPTION_LENGTH + " characters.");
        }
        return trimmed;
    }

    static BigDecimal price(BigDecimal value, List<String> violations) {
        if (value == null) {
            violations.add("price is required.");
            return null;
        }
        if (value.stripTrailingZeros().scale() > ProductLimits.PRICE_SCALE) {
            violations.add("price must have at most 2 decimal places.");
            return null;
        }
        if (value.compareTo(ProductLimits.MIN_PRICE) < 0 || value.compareTo(ProductLimits.MAX_PRICE) > 0) {
            violations.add("price must be between 0.01 and 99999.99.");
            return null;
        }
        return value.setScale(ProductLimits.PRICE_SCALE);
    }

    static void requirePositive(long value, String field, List<String> violations) {
        if (value <= 0) {
            violations.add(field + " must be positive.");
        }
    }

    static void throwIfAny(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new InvalidProductException(violations);
        }
    }

    private static String requiredText(String field, String value, int maxLength, List<String> violations) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            violations.add(field + " is required.");
        } else if (trimmed.length() > maxLength) {
            violations.add(field + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
