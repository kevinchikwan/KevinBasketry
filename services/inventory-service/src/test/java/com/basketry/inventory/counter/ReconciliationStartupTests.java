package com.basketry.inventory.counter;

import static org.awaitility.Awaitility.await;

import com.basketry.inventory.support.TestStockCounterConfig;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * With reconciliation on and a long interval, a counter is loaded right after
 * startup without anyone asking: the startup run. The stock row is inserted
 * before the application is ready (an ApplicationRunner runs before
 * ApplicationReadyEvent).
 */
@SpringBootTest(properties = {
        "basketry.stock-counter.reconciliation.enabled=true",
        "basketry.stock-counter.reconciliation.interval=1h"})
@Import({TestStockCounterConfig.class, ReconciliationStartupTests.StockBeforeReady.class})
class ReconciliationStartupTests {

    static final long PRODUCT = 424_242;

    @Autowired
    private StockCounter counter;

    @Test
    void theStartupRunLoadsTheCounters() {
        await().atMost(Duration.ofSeconds(10)).until(() -> counter.current(PRODUCT).equals(OptionalLong.of(7)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StockBeforeReady {

        @Bean
        ApplicationRunner insertStockBeforeReady(JdbcTemplate jdbc) {
            return args -> {
                jdbc.update("DELETE FROM stock_level WHERE product_id = ?", PRODUCT);
                jdbc.update("INSERT INTO stock_level (product_id, merchant_id, quantity, created_at, updated_at)"
                        + " VALUES (?, 3, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", PRODUCT);
            };
        }
    }
}
