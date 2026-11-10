package com.basketry.catalog.stockrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.basketry.catalog.inventory.InventoryClient;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Row 10 of the Day 12 review: no transaction is open while inventory-service
 * is called, so a slow inventory-service never holds a database connection or
 * a lock. Runs the application's own relay bean (a transactional proxy would
 * show here), with the client spied on. Mutation: make createPending
 * {@code @Transactional}.
 */
class RelayTransactionBoundaryTests extends CatalogServiceTestBase {

    @MockitoSpyBean
    private InventoryClient spiedClient;

    @Autowired
    private StockRowRelay relay;

    @Test
    void noTransactionIsOpenDuringACall() {
        givenProduct(MERCHANT, "Tea");
        List<Boolean> transactionDuringCall = new ArrayList<>();
        doAnswer(call -> {
            transactionDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(spiedClient).createStockRow(anyLong(), anyLong());

        assertThat(relay.createPending()).isTrue();

        assertThat(transactionDuringCall).containsExactly(false);
    }
}
