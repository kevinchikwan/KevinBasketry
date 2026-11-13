package com.basketry.order.checkout;

import java.math.BigDecimal;
import java.util.List;

/**
 * A checkout refused after its body was accepted; nothing is stored, so a
 * retry with the same Idempotency-Key runs again. {@link #code()} is the
 * problem detail's code (DESIGN.md Section 3, order-service). Each subclass is
 * one answer.
 */
public abstract class CheckoutRefusedException extends RuntimeException {

    private final String code;

    CheckoutRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 503: a key is not configured, or a downstream service could not answer. */
    public static class Unavailable extends CheckoutRefusedException {

        public static final String FINGERPRINT_KEY_NOT_CONFIGURED = "FINGERPRINT_KEY_NOT_CONFIGURED";
        public static final String INTERNAL_API_KEY_NOT_CONFIGURED = "INTERNAL_API_KEY_NOT_CONFIGURED";

        Unavailable(String code, String message) {
            super(code, message);
        }
    }

    /** 403: BUYER_DEACTIVATED or BUYER_NOT_FOUND. */
    public static class BuyerRefused extends CheckoutRefusedException {

        public static final String BUYER_DEACTIVATED = "BUYER_DEACTIVATED";
        public static final String BUYER_NOT_FOUND = "BUYER_NOT_FOUND";

        BuyerRefused(String code, String message) {
            super(code, message);
        }
    }

    /** 409: the same Idempotency-Key with another body. */
    public static class IdempotencyKeyReused extends CheckoutRefusedException {

        public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

        IdempotencyKeyReused() {
            super(CODE, "This Idempotency-Key was used for a different checkout.");
        }
    }

    /** 409: every refused line, in cart order, each with the first reason that applies. */
    public static class CartNotPurchasable extends CheckoutRefusedException {

        public static final String CODE = "CART_NOT_PURCHASABLE";

        private final List<RefusedLine> lines;

        CartNotPurchasable(List<RefusedLine> lines) {
            super(CODE, lines.size() + " line(s) cannot be bought.");
            this.lines = List.copyOf(lines);
        }

        public List<RefusedLine> lines() {
            return lines;
        }
    }

    /** 409: the server's total is not the one the buyer was shown; the current prices and total. */
    public static class PriceChanged extends CheckoutRefusedException {

        public static final String CODE = "PRICE_CHANGED";

        private final List<CurrentPrice> lines;
        private final BigDecimal total;

        PriceChanged(List<CurrentPrice> lines, BigDecimal total) {
            super(CODE, "A price changed since the total was shown.");
            this.lines = List.copyOf(lines);
            this.total = total;
        }

        public List<CurrentPrice> lines() {
            return lines;
        }

        public BigDecimal total() {
            return total;
        }
    }

    public record RefusedLine(long productId, RefusalReason reason) {
    }

    public record CurrentPrice(long productId, BigDecimal unitPrice) {
    }
}
