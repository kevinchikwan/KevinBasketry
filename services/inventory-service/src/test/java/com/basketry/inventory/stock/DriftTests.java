package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;

/**
 * Row 3 of the Day 10 review: a counter that has drifted from PostgreSQL, in
 * either direction, never changes an answer. Only PostgreSQL decides a success.
 */
class DriftTests extends InventoryServiceTestBase {

    /**
     * Left too low by a crash between the two stores: the confirming read
     * overrules it. The success is still subtracted from the counter, so the
     * drift stays the same size (5 below) instead of changing; reconciliation
     * repairs it.
     */
    @Test
    void aCounterBelowPostgresDoesNotRefuse() {
        givenStock(42, 5);
        counter.overwrite(42, 0);

        ReservationOutcome outcome = stock.reserve(reserve(7, 42, 1));

        assertThat(outcome).isEqualTo(new Reserved());
        assertThat(quantityOf(42)).isEqualTo(4);
        assertThat(counterOf(42)).hasValue(-1);
    }

    /** Left too high: the gate refuses, and the counter gets back what this call took. */
    @Test
    void aCounterAbovePostgresCannotOversell() {
        givenStock(42, 1);
        counter.overwrite(42, 100);

        ReservationOutcome outcome = stock.reserve(reserve(7, 42, 2));

        assertThat(outcome).isEqualTo(new ReservationFailed(ReasonCode.INSUFFICIENT_STOCK));
        assertThat(quantityOf(42)).isEqualTo(1);
        assertThat(counterOf(42)).hasValue(100);
    }

    @Test
    void aMissingCounterIsLoadedFromPostgres() {
        givenStock(42, 5);

        stock.reserve(reserve(7, 42, 1));

        assertThat(counterOf(42)).hasValue(4);
    }
}
