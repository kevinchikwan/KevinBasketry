package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Row 8 of the Day 10 review: the CHECKs of V1 hold what the application
 * always guarantees, so a bug that broke one would fail loudly instead of
 * storing a row that lies. Written with plain SQL, past the application.
 */
class DatabaseConstraintTests extends InventoryServiceTestBase {

    private static final String RESERVATION = "INSERT INTO stock_reservation"
            + " (order_id, product_id, quantity, status, reason_code, created_at, released_at)"
            + " VALUES (7, 42, 1, ?, ?, CURRENT_TIMESTAMP, ?)";

    @Test
    void stockNeverGoesNegative() {
        givenStock(42, 0);

        assertThatThrownBy(() -> jdbc.update("UPDATE stock_level SET quantity = -1 WHERE product_id = 42"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFailedRowNeedsAReasonAndOnlyAFailedRowHasOne() {
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "FAILED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "RESERVED", "INSUFFICIENT_STOCK", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exactlyAReleasedRowHasAReleaseTime() {
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "RELEASED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "RESERVED", null, java.sql.Timestamp.valueOf(
                "2026-11-09 10:00:00"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyKnownStatusesAndReasonCodesAreStored() {
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "PENDING", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(RESERVATION, "FAILED", "OUT_OF_STOCK", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
