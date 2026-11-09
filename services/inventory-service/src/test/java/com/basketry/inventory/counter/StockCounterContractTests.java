package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.counter.StockCounter.TakeResult;
import org.junit.jupiter.api.Test;

/**
 * The contract of a stock counter, run against the in-memory fake (default
 * run) and against Redis with the real Lua scripts (postgresTest), so the fake
 * the default run relies on behaves like the real thing.
 */
abstract class StockCounterContractTests {

    protected abstract StockCounter counter();

    @Test
    void takeTakesWhenThereIsEnough() {
        counter().overwrite(42, 5);

        assertThat(counter().take(42, 2)).isEqualTo(TakeResult.TAKEN);
        assertThat(counter().current(42)).hasValue(3);
    }

    @Test
    void takeTakesTheLastUnits() {
        counter().overwrite(42, 2);

        assertThat(counter().take(42, 2)).isEqualTo(TakeResult.TAKEN);
        assertThat(counter().current(42)).hasValue(0);
    }

    @Test
    void takeChangesNothingWhenThereIsNotEnough() {
        counter().overwrite(42, 1);

        assertThat(counter().take(42, 2)).isEqualTo(TakeResult.INSUFFICIENT);
        assertThat(counter().current(42)).hasValue(1);
    }

    /** "Not loaded" is not zero stock: the service loads the counter from PostgreSQL. */
    @Test
    void takeOfAMissingCounterIsNotLoadedAndCreatesNothing() {
        assertThat(counter().take(42, 1)).isEqualTo(TakeResult.NOT_LOADED);
        assertThat(counter().current(42)).isEmpty();
    }

    @Test
    void addIfLoadedAddsAndSubtracts() {
        counter().overwrite(42, 5);

        counter().addIfLoaded(42, 3);
        assertThat(counter().current(42)).hasValue(8);
        counter().addIfLoaded(42, -10);
        assertThat(counter().current(42)).hasValue(-2);
    }

    /** A plain INCRBY would create a counter holding only the delta. */
    @Test
    void addIfLoadedLeavesAMissingCounterMissing() {
        counter().addIfLoaded(42, 3);

        assertThat(counter().current(42)).isEmpty();
    }

    @Test
    void loadIfAbsentNeverOverwrites() {
        assertThat(counter().loadIfAbsent(42, 5)).isTrue();
        assertThat(counter().loadIfAbsent(42, 9)).isFalse();
        assertThat(counter().current(42)).hasValue(5);
    }

    @Test
    void overwriteReplacesWhateverWasThere() {
        counter().overwrite(42, 5);
        counter().overwrite(42, 9);

        assertThat(counter().current(42)).hasValue(9);
    }

    @Test
    void countersOfDifferentProductsAreSeparate() {
        counter().overwrite(42, 5);
        counter().overwrite(43, 1);

        counter().take(42, 1);

        assertThat(counter().current(42)).hasValue(4);
        assertThat(counter().current(43)).hasValue(1);
        assertThat(StockCounter.key(42)).isEqualTo("inventory:stock:42");
    }
}
