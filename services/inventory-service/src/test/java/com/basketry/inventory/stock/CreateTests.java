package com.basketry.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Row 5 of the Day 10 review: a product's stock row and its owner. */
class CreateTests extends InventoryServiceTestBase {

    /** Zero, so a new product shows as out of stock until its merchant adds some. */
    @Test
    void aNewProductStartsAtZero() {
        CreateOutcome outcome = stock.create(new CreateStockCommand(42, MERCHANT));

        assertThat(outcome).isEqualTo(new CreateOutcome(new StockSnapshot(42, MERCHANT, 0), true));
        assertThat(quantityOf(42)).isZero();
    }

    @Test
    void aRepeatByTheSameMerchantChangesNothing() {
        stock.create(new CreateStockCommand(42, MERCHANT));
        stock.adjust(new AdjustCommand(42, 5));

        CreateOutcome again = stock.create(new CreateStockCommand(42, MERCHANT));

        assertThat(again).isEqualTo(new CreateOutcome(new StockSnapshot(42, MERCHANT, 5), false));
    }

    @Test
    void anotherMerchantIsRefusedAndTheOwnerStays() {
        stock.create(new CreateStockCommand(42, MERCHANT));

        assertThatThrownBy(() -> stock.create(new CreateStockCommand(42, MERCHANT + 1)))
                .isInstanceOf(ProductOwnedByOtherMerchantException.class)
                .extracting(e -> ((StockChangeRefusedException) e).code())
                .isEqualTo("PRODUCT_OWNED_BY_OTHER_MERCHANT");
        assertThat(stockLevels.findById(42L).orElseThrow().getMerchantId()).isEqualTo(MERCHANT);
    }

    @Test
    void concurrentCreatesMakeOneRowAndAllSucceed() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CreateOutcome>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return stock.create(new CreateStockCommand(42, MERCHANT));
                }));
            }
            start.countDown();

            List<CreateOutcome> outcomes = new ArrayList<>();
            for (Future<CreateOutcome> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS)); // any exception fails here
            }
            assertThat(outcomes).filteredOn(CreateOutcome::created).hasSize(1);
            assertThat(stockLevels.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
