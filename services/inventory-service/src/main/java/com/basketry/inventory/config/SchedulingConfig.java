package com.basketry.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * For the two timers: the periodic stock counter reconciliation
 * (counter.ReconciliationScheduler) and the outbox relay
 * (outbox.OutboxRelayScheduler). They need a thread each
 * ({@code spring.task.scheduling.pool.size: 2}): with Spring's default of one,
 * a long reconciliation pass would hold up every result waiting to be published.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
