package com.basketry.promotion.web;

import java.util.Set;
import tools.jackson.core.JacksonException;

/**
 * A body that is not JSON of the pricing request's shape. It keeps only the
 * path of the request's own field names and indexes, never Jackson's message
 * or the exception itself: Jackson's message quotes the input (a coupon code,
 * a subtotal), and Spring logs a resolved exception's message at DEBUG.
 */
class UnreadableBodyException extends RuntimeException {

    static final String UNREADABLE = "The body is not JSON of the pricing request's shape.";

    /** The request's own field names, the only names the path may show. */
    private static final Set<String> FIELD_NAMES = Set.of("code", "merchants", "merchantId", "subtotal");

    UnreadableBodyException(JacksonException cause) {
        super(detail(path(cause)));
    }

    private static String detail(String path) {
        return path.isEmpty() ? UNREADABLE : UNREADABLE + " At: " + path + ".";
    }

    private static String path(JacksonException jackson) {
        if (jackson.getPath() == null) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : jackson.getPath()) {
            String name = reference.getPropertyName();
            if (name != null) {
                // Any other name (an unknown field given twice) is never shown.
                path.append(path.isEmpty() ? "" : ".").append(FIELD_NAMES.contains(name) ? name : "?");
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
