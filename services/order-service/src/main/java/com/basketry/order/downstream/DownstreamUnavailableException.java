package com.basketry.order.downstream;

/**
 * A downstream service could not give an answer checkout can use: a timeout, a
 * connection failure, a 5xx, a 401 (the API keys or the token's key differ), any
 * other status it never sends for a valid call, or a body that cannot be read.
 * Checkout answers 503 with {@link #code()} and creates nothing (DESIGN.md
 * Section 3, order-service). The message never quotes a request value.
 */
public class DownstreamUnavailableException extends RuntimeException {

    public static final String IDENTITY = "IDENTITY_UNAVAILABLE";
    public static final String CATALOG = "CATALOG_UNAVAILABLE";
    public static final String MERCHANT = "MERCHANT_UNAVAILABLE";

    private final String code;

    public DownstreamUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DownstreamUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
