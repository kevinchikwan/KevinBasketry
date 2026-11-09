package com.basketry.inventory.counter;

import static org.awaitility.Awaitility.await;

import com.basketry.inventory.support.InventoryServiceTestBase;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** With reconciliation on and a short interval, a counter left too low is repaired with no one asking. */
@SpringBootTest(properties = {
        "basketry.stock-counter.reconciliation.enabled=true",
        "basketry.stock-counter.reconciliation.interval=300ms"})
class ReconciliationPeriodicTests extends InventoryServiceTestBase {

    @Test
    void thePeriodicRunRepairsACounter() {
        givenStock(42, 7);
        counter.overwrite(42, 0);

        await().atMost(Duration.ofSeconds(10)).until(() -> counter.current(42).equals(OptionalLong.of(7)));
    }
}
