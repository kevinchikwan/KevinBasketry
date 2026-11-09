package com.basketry.inventory.counter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link StockReconciler} once at startup, in the background so startup
 * never waits for Redis, and then every {@code reconciliation.interval}.
 * Off in tests, which call the reconciler directly unless a test class turns
 * this on.
 */
@Component
@ConditionalOnBooleanProperty("basketry.stock-counter.reconciliation.enabled")
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final StockReconciler reconciler;

    public ReconciliationScheduler(StockReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAfterStartup() {
        Thread.ofVirtual().name("stock-reconciliation-startup").start(this::reconcile);
    }

    @Scheduled(initialDelayString = "${basketry.stock-counter.reconciliation.interval}",
            fixedDelayString = "${basketry.stock-counter.reconciliation.interval}")
    public void reconcilePeriodically() {
        reconcile();
    }

    /** Synchronized, so the startup run and the first periodic run never overlap. */
    private synchronized void reconcile() {
        try {
            reconciler.reconcileAll();
        } catch (RuntimeException e) {
            // PostgreSQL unavailable, most likely. The next run tries again.
            log.warn("Stock counter reconciliation failed: {}", e.toString());
        }
    }
}
