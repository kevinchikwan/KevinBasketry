package com.basketry.promotion.web;

import java.util.Set;
import tools.jackson.core.JacksonException;

/**
 * A body that is not JSON of its request's shape (the pricing request, or a
 * coupon request). It keeps only the path of the request's own field names
 * and indexes, never Jackson's message or the exception itself: Jackson's
 * message quotes the input (a coupon code, a subtotal, any value), and Spring
 * logs a resolved exception's message at DEBUG.
 */
class UnreadableBodyException extends RuntimeException {

    static final String UNREADABLE = "The body is not JSON of the pricing request's shape.";

    /** The pricing request's own field names, the only names the path may show. */
    private static final Set<String> FIELD_NAMES = Set.of("code", "merchants", "merchantId", "subtotal");

    /** The pricing request. */
    UnreadableBodyException(JacksonException cause) {
        this(UNREADABLE, FIELD_NAMES, cause);
    }

    /**
     * @param unreadable the request's fixed detail
     * @param fieldNames the request's own field names: any other name in the path is shown as ?
     */
    UnreadableBodyException(String unreadable, Set<String> fieldNames, JacksonException cause) {
        super(detail(unreadable, path(cause, fieldNames)));
    }

    /** No body at all: the fixed detail only. */
    UnreadableBodyException(String unreadable) {
        super(unreadable);
    }

    private static String detail(String unreadable, String path) {
        return path.isEmpty() ? unreadable : unreadable + " At: " + path + ".";
    }

    private static String path(JacksonException jackson, Set<String> fieldNames) {
        if (jackson.getPath() == null) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : jackson.getPath()) {
            String name = reference.getPropertyName();
            if (name != null) {
                // Any other name (an unknown field given twice) is never shown.
                path.append(path.isEmpty() ? "" : ".").append(fieldNames.contains(name) ? name : "?");
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
