package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Commands are validated on construction, so an invalid request never reaches
 * the database (a database error would be retried forever by the listener).
 * Messages name the field, never the value.
 */
class CommandValidationTests {

    @Test
    void aReserveNeedsPositiveIdsAndQuantity() {
        assertThatThrownBy(() -> new ReserveCommand(0, 42, 1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("orderId must be positive");
        assertThatThrownBy(() -> new ReserveCommand(7, -1, 1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("productId must be positive");
        assertThatThrownBy(() -> new ReserveCommand(7, 42, 0)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("quantity must be positive");
        // Above the reservation limit is valid: it is answered with QUANTITY_OVER_LIMIT.
        assertThatCode(() -> new ReserveCommand(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE))
                .doesNotThrowAnyException();
    }

    @Test
    void aReleaseNeedsPositiveIdsAndQuantity() {
        assertThatThrownBy(() -> new ReleaseCommand(-7, 42, 1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("orderId must be positive");
        assertThatThrownBy(() -> new ReleaseCommand(7, 0, 1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("productId must be positive");
        assertThatThrownBy(() -> new ReleaseCommand(7, 42, -1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("quantity must be positive");
    }

    @Test
    void anAdjustmentIsANonZeroDeltaWithinTheLimit() {
        assertThatThrownBy(() -> new AdjustCommand(0, 1)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("productId must be positive");
        assertThatThrownBy(() -> new AdjustCommand(42, 0)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("delta must not be 0");
        for (long tooLarge : new long[] {1_000_001, -1_000_001, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> new AdjustCommand(42, tooLarge)).isInstanceOf(InvalidStockRequestException.class)
                    .hasMessage("delta must be between -1000000 and 1000000");
        }
        assertThatCode(() -> new AdjustCommand(42, 1_000_000)).doesNotThrowAnyException();
        assertThatCode(() -> new AdjustCommand(42, -1_000_000)).doesNotThrowAnyException();
    }

    @Test
    void aCreateNeedsPositiveIds() {
        assertThatThrownBy(() -> new CreateStockCommand(0, 3)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("productId must be positive");
        assertThatThrownBy(() -> new CreateStockCommand(42, 0)).isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("merchantId must be positive");
    }
}
