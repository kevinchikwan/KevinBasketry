package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;

/**
 * Row 8 of the Day 10 review: the largest request validation lets through fits
 * every column. A database error is retried forever by the listener, so a
 * valid request that did not fit would block its partition for good. Runs on
 * H2 and, in postgresTest, on PostgreSQL.
 */
class ColumnLimitsTests extends InventoryServiceTestBase {

    private static final long MAX_ID = Long.MAX_VALUE;

    @Test
    void theLargestValidIdsAndQuantitiesAreStored() {
        stock.create(new CreateStockCommand(MAX_ID, MAX_ID));
        stock.adjust(new AdjustCommand(MAX_ID, StockLimits.MAX_ADJUSTMENT));

        assertThat(stock.reserve(reserve(MAX_ID, MAX_ID, StockLimits.MAX_RESERVATION_QUANTITY)))
                .isEqualTo(new Reserved());
        stock.release(release(MAX_ID, MAX_ID, StockLimits.MAX_RESERVATION_QUANTITY));
        // The largest quantity a request can carry, stored on a FAILED row and on a tombstone.
        assertThat(stock.reserve(reserve(MAX_ID - 1, MAX_ID, Integer.MAX_VALUE)))
                .isEqualTo(new ReservationFailed(ReasonCode.QUANTITY_OVER_LIMIT));
        stock.release(release(MAX_ID - 2, MAX_ID, Integer.MAX_VALUE));

        assertThat(quantityOf(MAX_ID)).isEqualTo(StockLimits.MAX_ADJUSTMENT);
        assertThat(reservation(MAX_ID - 1, MAX_ID).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(reservation(MAX_ID - 2, MAX_ID).getReasonCode()).isEqualTo(ReasonCode.RELEASED_BEFORE_RESERVE);
        assertThat(outboxRows()).hasSize(4).extracting(OutboxEvent::getMessageKey)
                .contains(Long.toString(MAX_ID), Long.toString(MAX_ID - 1), Long.toString(MAX_ID - 2));
    }

    /**
     * Stock at the limit, a reservation, a restock back to the limit, then the
     * release: the stock ends above the limit. There is no upper CHECK in the
     * database, or this release would fail forever.
     */
    @Test
    void aReleaseCanLiftStockAboveTheLimit() {
        givenStockAndCounter(42, StockLimits.MAX_STOCK);
        stock.reserve(reserve(7, 42, 1_000));
        stock.adjust(new AdjustCommand(42, 1_000));

        stock.release(release(7, 42, 1_000));

        assertThat(quantityOf(42)).isEqualTo(StockLimits.MAX_STOCK + 1_000);
        assertThat(counterOf(42)).hasValue(StockLimits.MAX_STOCK + 1_000);
    }
}
