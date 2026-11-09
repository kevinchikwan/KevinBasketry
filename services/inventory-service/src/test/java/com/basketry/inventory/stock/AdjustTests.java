package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.inventory.counter.StockCounter.TakeResult;
import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;

/** Row 6 of the Day 10 review: adjustments are deltas, and commute with reservations. */
class AdjustTests extends InventoryServiceTestBase {

    @Test
    void anIncreaseAddsToBothStores() {
        givenStockAndCounter(42, 5);

        StockSnapshot after = stock.adjust(new AdjustCommand(42, 10));

        assertThat(after).isEqualTo(new StockSnapshot(42, MERCHANT, 15));
        assertThat(quantityOf(42)).isEqualTo(15);
        assertThat(counterOf(42)).hasValue(15);
    }

    @Test
    void aDecreaseToZeroIsAllowed() {
        givenStockAndCounter(42, 5);

        assertThat(stock.adjust(new AdjustCommand(42, -5)).quantity()).isZero();
        assertThat(counterOf(42)).hasValue(0);
    }

    @Test
    void aDecreaseBelowZeroIsRefusedAndChangesNothing() {
        givenStockAndCounter(42, 5);

        assertThatThrownBy(() -> stock.adjust(new AdjustCommand(42, -6)))
                .isInstanceOf(StockWouldGoNegativeException.class)
                .hasMessage("Product 42 has 5 in stock; a change of -6 would take it below zero")
                .extracting(e -> ((StockChangeRefusedException) e).code()).isEqualTo("STOCK_WOULD_GO_NEGATIVE");
        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
    }

    @Test
    void anIncreaseUpToTheLimitIsAllowedAndBeyondItRefused() {
        givenStockAndCounter(42, StockLimits.MAX_STOCK - 2);

        assertThatThrownBy(() -> stock.adjust(new AdjustCommand(42, 3)))
                .isInstanceOf(StockLimitExceededException.class)
                .extracting(e -> ((StockChangeRefusedException) e).code()).isEqualTo("STOCK_LIMIT_EXCEEDED");
        assertThat(stock.adjust(new AdjustCommand(42, 2)).quantity()).isEqualTo(StockLimits.MAX_STOCK);
        assertThat(counterOf(42)).hasValue(StockLimits.MAX_STOCK);
    }

    /** Releases can lift stock above the limit; a merchant must still be able to bring it down. */
    @Test
    void aDecreaseIsAllowedAboveTheLimit() {
        givenStockAndCounter(42, 1_500_000);

        assertThat(stock.adjust(new AdjustCommand(42, -1)).quantity()).isEqualTo(1_499_999);
    }

    @Test
    void anUnknownProductIsRefused() {
        assertThatThrownBy(() -> stock.adjust(new AdjustCommand(42, 1)))
                .isInstanceOf(UnknownProductException.class)
                .extracting(e -> ((StockChangeRefusedException) e).code()).isEqualTo("UNKNOWN_PRODUCT");
        assertThat(stockLevels.findAll()).isEmpty();
        assertThat(counterOf(42)).isEmpty();
    }

    /**
     * A reservation in flight has taken 3 from the counter and not yet committed
     * in PostgreSQL. The adjustment's delta keeps that 3 taken; a SET from
     * PostgreSQL would give it back.
     */
    @Test
    void anAdjustmentKeepsWhatReservationsInFlightTookFromTheCounter() {
        givenStockAndCounter(42, 10);
        assertThat(counter.take(42, 3)).isEqualTo(TakeResult.TAKEN);

        stock.adjust(new AdjustCommand(42, 5));

        assertThat(quantityOf(42)).isEqualTo(15);
        assertThat(counterOf(42)).hasValue(12);
    }

    /** A missing counter stays missing; it is loaded from PostgreSQL when next needed. */
    @Test
    void anAdjustmentDoesNotCreateAMissingCounter() {
        givenStock(42, 5);

        stock.adjust(new AdjustCommand(42, 5));

        assertThat(counterOf(42)).isEmpty();
    }
}
