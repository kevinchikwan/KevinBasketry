package com.basketry.order.web;

import java.util.Set;
import tools.jackson.core.JacksonException;

/**
 * A checkout body that is not JSON of the checkout's shape. It keeps only the
 * path of the request's own field names and indexes, never Jackson's message
 * or the exception itself: Jackson's message quotes the input ("Cannot coerce
 * String value (...)" for a card number sent as expectedTotal), and Spring
 * logs a resolved exception's message at DEBUG (HttpJacksonMessageLeakTests).
 * The same as promotion-service's.
 */
class UnreadableBodyException extends RuntimeException {

    /** CheckoutRequest's own field names, the only names the path may show; any other is "?". */
    private static final Set<String> FIELD_NAMES =
            Set.of("lines", "productId", "quantity", "couponCode", "cardNumber", "expectedTotal");

    /** The body was missing or the JSON literal null: the fixed detail alone. */
    UnreadableBodyException() {
        super(OrderExceptionHandler.UNREADABLE);
    }

    UnreadableBodyException(JacksonException cause) {
        super(detail(path(cause)));
    }

    private static String detail(String path) {
        return path.isEmpty() ? OrderExceptionHandler.UNREADABLE : OrderExceptionHandler.UNREADABLE + " At: " + path + ".";
    }

    /** Package-private for UnreadableBodyExceptionTests. */
    static String path(JacksonException jackson) {
        if (jackson.getPath() == null) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : jackson.getPath()) {
            String name = reference.getPropertyName();
            if (name != null) {
                // Any other name (a field name could be a card number) is never shown.
                path.append(path.isEmpty() ? "" : ".").append(FIELD_NAMES.contains(name) ? name : "?");
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
