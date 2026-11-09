package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * DESIGN.md Section 2, inventory-service: reservations, their outcomes, and
 * what a repeated request does. Runs on H2 with the fake counter and, in
 * postgresTest, on PostgreSQL with Redis.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReserveTests extends InventoryServiceTestBase {

    @Test
    void aReserveTakesTheStockFromBothStoresAndWritesReserved() {
        givenStockAndCounter(42, 5);

        ReservationOutcome outcome = stock.reserve(reserve(7, 42, 2));

        assertThat(outcome).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(counterOf(42)).hasValue(3);
        StockReservation row = reservation(7, 42);
        assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(row.getQuantity()).isEqualTo(2);
        assertThat(row.getReasonCode()).isNull();
        assertThat(row.getReleasedAt()).isNull();
        OutboxEvent event = outboxRows().getFirst();
        assertThat(outboxRows()).hasSize(1);
        assertThat(event.getTopic()).isEqualTo("order.stock.reserved");
        assertThat(event.getMessageKey()).isEqualTo("7");
        assertThat(payloadOf(event))
                .containsEntry("schemaVersion", 1)
                .containsEntry("eventId", event.getEventId().toString())
                .containsEntry("orderId", 7)
                .containsEntry("productId", 42)
                .containsKey("occurredAt")
                .hasSize(5);
    }

    @Test
    void theLastUnitsCanBeTaken() {
        givenStockAndCounter(42, 2);

        assertThat(stock.reserve(reserve(7, 42, 2))).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isZero();
        assertThat(counterOf(42)).hasValue(0);
    }

    @Test
    void notEnoughStockFailsTakesNothingAndIsStored() {
        givenStockAndCounter(42, 1);

        ReservationOutcome outcome = stock.reserve(reserve(7, 42, 2));

        assertThat(outcome).isEqualTo(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK));
        assertThat(quantityOf(42)).isEqualTo(1);
        assertThat(counterOf(42)).hasValue(1);
        StockReservation row = reservation(7, 42);
        assertThat(row.getStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(row.getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_STOCK);
        OutboxEvent event = outboxRows().getFirst();
        assertThat(outboxRows()).hasSize(1);
        assertThat(event.getTopic()).isEqualTo("order.stock.reservation-failed");
        assertThat(event.getMessageKey()).isEqualTo("7");
        assertThat(payloadOf(event))
                .containsEntry("schemaVersion", 1)
                .containsEntry("eventId", event.getEventId().toString())
                .containsEntry("orderId", 7)
                .containsEntry("productId", 42)
                .containsEntry("reasonCode", "INSUFFICIENT_STOCK")
                .containsKey("occurredAt")
                .hasSize(6);
    }

    /** No counter is created for a product that does not exist. */
    @Test
    void anUnknownProductFailsAndGetsNoCounter() {
        ReservationOutcome outcome = stock.reserve(reserve(7, 42, 1));

        assertThat(outcome).isEqualTo(new ReservationFailed(ReasonCode.UNKNOWN_PRODUCT));
        assertThat(reservation(7, 42).getReasonCode()).isEqualTo(ReasonCode.UNKNOWN_PRODUCT);
        assertThat(counterOf(42)).isEmpty();
        assertThat(topics()).containsExactly("order.stock.reservation-failed");
    }

    @Test
    void aQuantityOverTheLimitFailsWithoutTouchingEitherStore() {
        givenStockAndCounter(42, 5_000);

        ReservationOutcome over = stock.reserve(reserve(7, 42, StockLimits.MAX_RESERVATION_QUANTITY + 1));
        ReservationOutcome atLimit = stock.reserve(reserve(8, 42, StockLimits.MAX_RESERVATION_QUANTITY));

        assertThat(over).isEqualTo(new ReservationFailed(ReasonCode.QUANTITY_OVER_LIMIT));
        assertThat(reservation(7, 42).getQuantity()).isEqualTo(1_001);
        assertThat(atLimit).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(4_000);
        assertThat(counterOf(42)).hasValue(4_000);
    }

    /** Row 1 of the Day 10 review: the stored row answers before Redis is touched. */
    @Test
    void aRedeliveredReserveRepublishesAndLeavesTheCounterAlone() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 2));

        ReservationOutcome again = stock.reserve(reserve(7, 42, 2));

        assertThat(again).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(counterOf(42)).hasValue(3);
        assertThat(topics()).containsExactly("order.stock.reserved", "order.stock.reserved");
    }

    /**
     * Without the FAILED row, the redelivery would succeed after the restock,
     * for an order that order-service has already failed: stock nobody releases.
     */
    @Test
    void aRedeliveredFailureStillFailsAfterARestock() {
        givenStockAndCounter(42, 0);
        stock.reserve(reserve(7, 42, 1));
        stock.adjust(new AdjustCommand(42, 10));

        ReservationOutcome again = stock.reserve(reserve(7, 42, 1));

        assertThat(again).isEqualTo(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK));
        assertThat(quantityOf(42)).isEqualTo(10);
        assertThat(counterOf(42)).hasValue(10);
        assertThat(topics()).containsExactly("order.stock.reservation-failed", "order.stock.reservation-failed");
    }

    @Test
    void aRepeatWithAnotherQuantityKeepsTheFirstResultAndWarns(CapturedOutput output) {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 2));

        ReservationOutcome again = stock.reserve(reserve(7, 42, 3));

        assertThat(again).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(reservation(7, 42).getQuantity()).isEqualTo(2);
        assertWarnedOnce(output, "Repeated reserve for order 7, product 42 differs from the first: quantity 3"
                + " (first 2)");
    }

    /** Row 2 of the Day 10 review: a deleted row would make the replay look new. */
    @Test
    void aReserveReplayedAfterItsReleaseTakesNothing() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 2));
        stock.release(release(7, 42, 2));

        ReservationOutcome replay = stock.reserve(reserve(7, 42, 2));

        assertThat(replay).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(reservation(7, 42).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(topics()).containsExactly("order.stock.reserved", "order.stock.released",
                "order.stock.reserved");
    }
}
