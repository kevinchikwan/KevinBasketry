package com.basketry.order.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The requests order-service publishes, exactly the fields payment-service's
 * and inventory-service's parsers accept (DESIGN.md Section 5, payment-service
 * requests and inventory-service requests): schemaVersion 1, a new eventId per
 * row, occurredAt. Field order is the record's. Golden JSON files of each are
 * checked in the tests (contracts/*.json).
 */
public final class OrderRequests {

    public static final int SCHEMA_VERSION = 1;

    public static final String CHARGE_TOPIC = "order.merchant-charge.requested";
    public static final String REFUND_TOPIC = "order.merchant-refund.requested";
    public static final String RESERVE_TOPIC = "order.stock-reserve.requested";
    public static final String RELEASE_TOPIC = "order.stock-release.requested";
    public static final String SUCCEEDED_TOPIC = "order.succeeded";
    public static final String FAILED_TOPIC = "order.failed";

    /** The topics whose unsent rows the timer cancels: the requests a failed order must not send. */
    public static final List<String> CANCELLABLE_TOPICS = List.of(CHARGE_TOPIC, RESERVE_TOPIC);

    private OrderRequests() {
    }

    /**
     * {@code amount} at scale 2. The card number is the only copy outside the
     * checkout request; {@link #toString()} never shows it.
     */
    public record MerchantChargeRequested(int schemaVersion, UUID eventId, long orderId, long merchantId,
                                          BigDecimal amount, String cardNumber, Instant occurredAt) {

        @Override
        public String toString() {
            return "MerchantChargeRequested[orderId=" + orderId + ", merchantId=" + merchantId
                    + ", amount=" + amount + ", cardNumber=***]";
        }
    }

    public record MerchantRefundRequested(int schemaVersion, UUID eventId, long orderId, long merchantId,
                                          String transactionRef, Instant occurredAt) {
    }

    public record StockReserveRequested(int schemaVersion, UUID eventId, long orderId, long productId,
                                        long quantity, Instant occurredAt) {
    }

    /** The line's quantity; inventory-service gives back what it stored, whatever this says. */
    public record StockReleaseRequested(int schemaVersion, UUID eventId, long orderId, long productId,
                                        long quantity, Instant occurredAt) {
    }
}
