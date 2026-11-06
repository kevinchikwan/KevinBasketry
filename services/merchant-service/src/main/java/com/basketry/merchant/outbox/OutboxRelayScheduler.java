package com.basketry.merchant.outbox;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay on a timer and backs off while passes fail. fixedDelay means
 * passes never overlap. Publishing does not need a leader: with two instances
 * a row may go out twice, which consumers drop through {@code version}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "basketry.outbox.relay.enabled", havingValue = "true")
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final RelayBackoff backoff;

    OutboxRelayScheduler(OutboxRelay relay, OutboxProperties properties) {
        this.relay = relay;
        this.backoff = new RelayBackoff(properties.relay().backoffInitial(), properties.relay().backoffMax());
    }

    @Scheduled(fixedDelayString = "${basketry.outbox.relay.interval}")
    void tick() {
        if (!backoff.isReady(Instant.now())) {
            return;
        }
        boolean published;
        try {
            published = relay.publishPending();
        } catch (RuntimeException failed) {
            // For example the database. Nothing is lost: the rows are still PENDING.
            log.warn("Outbox relay pass failed: {}", failed.toString());
            published = false;
        }
        if (published) {
            backoff.succeeded();
        } else {
            backoff.failed(Instant.now());
        }
    }
}
