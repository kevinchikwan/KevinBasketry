package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.stock.AdjustCommand;
import com.basketry.inventory.stock.ReasonCode;
import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InMemoryStockCounter;
import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Row 4 of the Day 10 review: with Redis down, every operation goes on with
 * PostgreSQL alone, still correct, and nothing that was not known to be taken
 * is ever given back to the counter. Uses a fake counter with faults whatever
 * the run.
 */
class FallbackTests extends InventoryServiceTestBase {

    private final InMemoryStockCounter fake = new InMemoryStockCounter();
    private final MutableClock clock = new MutableClock();

    @Test
    void withRedisDownReservationsGoToTheGateAloneAndNothingIsGivenBack() {
        givenStock(42, 5);
        fake.overwrite(42, 5);
        fake.setUnavailable(true);
        StockService service = serviceOn(fake, clock);

        assertThat(service.reserve(reserve(7, 42, 2))).isEqualTo(new Reserved());
        assertThat(service.reserve(reserve(8, 42, 4)))
                .isEqualTo(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK));

        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(fake.current(42)).hasValue(5); // too high now: harmless, reconciliation repairs it
    }

    /**
     * The counter took the stock but the reply was lost. The gate refuses; the
     * counter must not get back what it may not have taken. No skip window here,
     * so a wrong give-back would really reach the counter instead of being
     * skipped by the guard.
     */
    @Test
    void aTakeWhoseReplyWasLostIsNotGivenBack() {
        givenStock(42, 0);
        fake.overwrite(42, 5);
        fake.loseNextReply();
        StockService noSkipWindow = new StockService(stockLevels, reservations, resultOutbox,
                new StockCounterGuard(fake, new StockCounterProperties(Duration.ZERO,
                        counterProperties.reconciliation()), clock), transactionManager);

        ReservationFailed failed = (ReservationFailed) noSkipWindow.reserve(reserve(7, 42, 1));

        assertThat(failed.reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_STOCK);
        assertThat(fake.current(42)).hasValue(4);
    }

    @Test
    void releaseAndAdjustWorkWithRedisDown() {
        givenStock(42, 5);
        fake.overwrite(42, 5);
        StockService service = serviceOn(fake, clock);
        service.reserve(reserve(7, 42, 2));
        fake.setUnavailable(true);

        service.release(release(7, 42, 2));
        service.adjust(new AdjustCommand(42, 10));

        assertThat(quantityOf(42)).isEqualTo(15);
        assertThat(fake.current(42)).hasValue(3);
    }
}
