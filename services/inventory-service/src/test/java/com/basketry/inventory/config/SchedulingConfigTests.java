package com.basketry.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The counter reconciliation and the outbox relay each get a thread: with
 * Spring's default of one, a long reconciliation pass would hold up every
 * result waiting to be published.
 */
class SchedulingConfigTests extends InventoryServiceTestBase {

    @Autowired
    private ThreadPoolTaskScheduler scheduler;

    @Test
    void theTwoTimersHaveAThreadEach() {
        // getPoolSize() counts the threads alive now; the configured size is the core size.
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
    }
}
