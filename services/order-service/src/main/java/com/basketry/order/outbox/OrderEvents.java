package com.basketry.order.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * order.succeeded and order.failed (DESIGN.md Section 5): what order-service
 * tells the rest of the system about an order's outcome. Field order is the
 * record's; golden JSON files of both are checked in the tests. Neither carries
 * any part of the card number. Every money value is a JSON number at scale 2.
 */
public final class OrderEvents {

    public static final int SCHEMA_VERSION = 1;

    private OrderEvents() {
    }

    /**
     * One per order, written in the transaction that decides SUCCEEDED.
     * platform-service and promotion-service will need nothing else.
     */
    public record OrderSucceeded(int schemaVersion, UUID eventId, long orderId, String orderReference,
                                 long buyerAccountId, String couponCode, BigDecimal totalAmount,
                                 List<Merchant> merchants, List<Item> items, Instant occurredAt) {
    }

    /** One entry per charge leg. */
    public record Merchant(long merchantId, BigDecimal subtotal, BigDecimal discount, BigDecimal amount,
                           BigDecimal commissionRate, BigDecimal commission, String transactionRef) {
    }

    public record Item(long productId, long merchantId, long quantity, BigDecimal unitPrice,
                       BigDecimal lineAmount) {
    }

    /**
     * Written in the transaction that makes the order FAILED, and again, with a
     * new eventId, after a reopen. A consumer must expect more than one per order.
     */
    public record OrderFailed(int schemaVersion, UUID eventId, long orderId, String orderReference,
                              List<Reason> reasons, Instant occurredAt) {
    }

    /**
     * A leg that failed the order: a declined charge (its response code), a
     * failed reservation (its reason code) or a leg that timed out (NO_RESULT).
     * {@code merchantId} for a CHARGE, {@code productId} for a RESERVE; the other
     * is left out.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reason(String leg, Long merchantId, Long productId, String reasonCode) {

        public static Reason charge(long merchantId, String reasonCode) {
            return new Reason("CHARGE", merchantId, null, reasonCode);
        }

        public static Reason reserve(long productId, String reasonCode) {
            return new Reason("RESERVE", null, productId, reasonCode);
        }
    }
}
