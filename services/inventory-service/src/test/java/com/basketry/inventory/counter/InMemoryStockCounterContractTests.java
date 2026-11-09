package com.basketry.inventory.counter;

import com.basketry.inventory.support.InMemoryStockCounter;

/** The contract against the fake the default run uses everywhere. */
class InMemoryStockCounterContractTests extends StockCounterContractTests {

    private final InMemoryStockCounter counter = new InMemoryStockCounter();

    @Override
    protected StockCounter counter() {
        return counter;
    }
}
