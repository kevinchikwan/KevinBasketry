package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Row 2 of the Day 10 review: a release in each state of the reservation row.
 * Every release publishes {@code released}; stock goes back exactly once.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReleaseTests extends InventoryServiceTestBase {

    @Test
    void aReleaseGivesTheStockBackToBothStoresAndWritesReleased() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 3));

        Released released = stock.release(release(7, 42, 3));

        assertThat(released).isEqualTo(new Released(7, 42));
        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        StockReservation row = reservation(7, 42);
        assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(row.getReleasedAt()).isNotNull();
        OutboxEvent event = outboxRows().getLast();
        assertThat(event.getTopic()).isEqualTo("order.stock.released");
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
    void aRepeatedReleaseGivesNothingBackAndPublishesAgain() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 3));
        stock.release(release(7, 42, 3));

        stock.release(release(7, 42, 3));

        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(topics()).containsExactly("order.stock.reserved", "order.stock.released",
                "order.stock.released");
    }

    @Test
    void aReleaseOfAFailedReservationGivesNothingBack() {
        givenStockAndCounter(42, 0);
        stock.reserve(reserve(7, 42, 3));
        stock.adjust(new AdjustCommand(42, 5));

        stock.release(release(7, 42, 3));

        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(reservation(7, 42).getStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(topics()).containsExactly("order.stock.reservation-failed", "order.stock.released");
    }

    /** A late reserve after a release that came first must take nothing: no one would release it. */
    @Test
    void aReleaseBeforeAnyReserveLeavesATombstoneThatALateReserveCannotPass(CapturedOutput output) {
        givenStockAndCounter(42, 5);

        stock.release(release(7, 42, 3));
        ReservationOutcome late = stock.reserve(reserve(7, 42, 3));

        assertThat(late).isEqualTo(new ReservationFailed(ReasonCode.RELEASED_BEFORE_RESERVE));
        StockReservation row = reservation(7, 42);
        assertThat(row.getStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(row.getReasonCode()).isEqualTo(ReasonCode.RELEASED_BEFORE_RESERVE);
        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(topics()).containsExactly("order.stock.released", "order.stock.reservation-failed");
        assertWarnedOnce(output, "Release for order 7, product 42 came before any reserve");
    }

    @Test
    void aReleaseWithAnotherQuantityGivesBackTheStoredOne(CapturedOutput output) {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 3));

        stock.release(release(7, 42, 4));

        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertWarnedOnce(output, "quantity 4 (stored 3). Releasing the stored quantity.");
    }

    /**
     * Two overlapping releases both read RESERVED; this is the second one, made
     * to read a stale RESERVED copy after the first has committed. Its
     * conditional update must match nothing, and nothing goes back twice.
     */
    @Test
    void theSecondOfTwoOverlappingReleasesGivesNothingBack() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 3));
        StockReservation staleReserved = reservation(7, 42);
        stock.release(release(7, 42, 3));
        StockService second = serviceWith(firstLookupAnswers(Optional.of(staleReserved)));

        second.release(release(7, 42, 3));

        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
        assertThat(topics()).containsExactly("order.stock.reserved", "order.stock.released",
                "order.stock.released");
    }
}
