package com.basketry.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.inventory.counter.StockCounterGuard;
import com.basketry.inventory.stock.ReasonCode;
import com.basketry.inventory.stock.ReservationStatus;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The result and its outbox row commit together or not at all (DESIGN.md
 * Section 5). And when the transaction fails, the counter gets back what the
 * call took from it.
 */
class OutboxAtomicityTests extends InventoryServiceTestBase {

    @Test
    void theOutboxWriterRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> resultOutbox.recordReserved(7, 42))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> resultOutbox.recordReservationFailed(7, 42, ReasonCode.INSUFFICIENT_STOCK))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> resultOutbox.recordReleased(7, 42))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void whenTheOutboxWriteFailsNothingIsReservedAndTheCounterIsRestored() {
        givenStockAndCounter(42, 5);

        assertThatThrownBy(() -> failingService().reserve(reserve(7, 42, 2))).hasMessage("outbox write failed");

        assertThat(reservations.findAll()).isEmpty();
        assertThat(outboxRows()).isEmpty();
        assertThat(quantityOf(42)).isEqualTo(5);
        assertThat(counterOf(42)).hasValue(5);
    }

    @Test
    void whenTheOutboxWriteFailsNothingIsReleased() {
        givenStockAndCounter(42, 5);
        stock.reserve(reserve(7, 42, 2));

        assertThatThrownBy(() -> failingService().release(release(7, 42, 2))).hasMessage("outbox write failed");

        assertThat(reservation(7, 42).getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(quantityOf(42)).isEqualTo(3);
        assertThat(counterOf(42)).hasValue(3);
        assertThat(outboxRows()).hasSize(1);
    }

    /** A rollback of a surrounding transaction takes both rows with it. */
    @Test
    void aRollbackRemovesBothTheReservationAndItsOutboxRow() {
        givenStock(42, 5);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            resultOutbox.recordReserved(7, 42);
            jdbc.update("UPDATE stock_level SET quantity = 3 WHERE product_id = 42");
            status.setRollbackOnly();
        });

        assertThat(outboxRows()).isEmpty();
        assertThat(quantityOf(42)).isEqualTo(5);
    }

    private StockService failingService() {
        return new StockService(stockLevels, reservations, new FailingOutbox(outbox, json),
                new StockCounterGuard(counter, counterProperties), transactionManager);
    }

    /** Writes its row, then fails, inside the caller's transaction. */
    private static final class FailingOutbox extends StockResultOutbox {

        FailingOutbox(OutboxRepository outbox, JsonMapper json) {
            super(outbox, json);
        }

        @Override
        public void recordReserved(long orderId, long productId) {
            super.recordReserved(orderId, productId);
            throw new IllegalStateException("outbox write failed");
        }

        @Override
        public void recordReleased(long orderId, long productId) {
            super.recordReleased(orderId, productId);
            throw new IllegalStateException("outbox write failed");
        }
    }
}
