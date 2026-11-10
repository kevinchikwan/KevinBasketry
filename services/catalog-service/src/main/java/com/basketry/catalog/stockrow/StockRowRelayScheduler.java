package com.basketry.catalog.stockrow;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay on a timer and backs off while passes fail, as
 * inventory-service's outbox relay. fixedDelay means passes never overlap.
 */
@Component
@ConditionalOnProperty(name = "basketry.stock-rows.relay.enabled", havingValue = "true")
class StockRowRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockRowRelayScheduler.class);

    private final StockRowRelay relay;
    private final RelayBackoff backoff;

    StockRowRelayScheduler(StockRowRelay relay, StockRowProperties properties) {
        this.relay = relay;
        this.backoff = new RelayBackoff(properties.relay().backoffInitial(), properties.relay().backoffMax());
    }

    @Scheduled(fixedDelayString = "${basketry.stock-rows.relay.interval}")
    void tick() {
        if (!backoff.isReady(Instant.now())) {
            return;
        }
        boolean done;
        try {
            done = relay.createPending();
        } catch (RuntimeException failed) {
            // For example the database. Nothing is lost: the requests are still PENDING.
            log.warn("Stock-row relay pass failed: {}", failed.toString());
            done = false;
        }
        if (done) {
            backoff.succeeded();
        } else {
            backoff.failed(Instant.now());
        }
    }
}
