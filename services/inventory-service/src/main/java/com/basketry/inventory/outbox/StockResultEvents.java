package com.basketry.inventory.outbox;

import com.basketry.inventory.stock.ReasonCode;
import java.time.Instant;
import java.util.UUID;

/**
 * The payloads inventory-service publishes (DESIGN.md Section 5). Each carries
 * the fields of the event catalog plus, as in payment-service,
 * {@code schemaVersion} (to detect a format change), {@code eventId} (tracing
 * only; duplicates are dropped by orderId and productId) and
 * {@code occurredAt}. No result carries a quantity.
 */
public final class StockResultEvents {

    public static final int SCHEMA_VERSION = 1;

    public static final String RESERVED_TOPIC = "order.stock.reserved";
    public static final String RESERVATION_FAILED_TOPIC = "order.stock.reservation-failed";
    public static final String RELEASED_TOPIC = "order.stock.released";

    private StockResultEvents() {
    }

    public record StockReserved(int schemaVersion, UUID eventId, long orderId, long productId,
                                Instant occurredAt) {
    }

    public record StockReservationFailed(int schemaVersion, UUID eventId, long orderId, long productId,
                                         ReasonCode reasonCode, Instant occurredAt) {
    }

    public record StockReleased(int schemaVersion, UUID eventId, long orderId, long productId,
                                Instant occurredAt) {
    }
}
