package com.basketry.inventory.outbox;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Publishes unsent outbox rows in id order, each to the topic it names, keyed
 * by orderId (DESIGN.md Section 5). Each row is sent and acknowledged, then
 * marked in its own short transaction, so no database transaction is open
 * during a send. Not {@code @Transactional}: one transaction around a pass
 * would roll back the marks of rows already sent when a later one fails.
 *
 * <ul>
 *   <li>A failure stops the pass, so no later row is sent before the one that
 *       failed. The row stays PENDING.</li>
 *   <li>Ids follow insert order, not commit order, so a row can go out after a
 *       row with a higher id. That is harmless here: every result one topic
 *       carries for one (orderId, productId) has the same outcome.</li>
 *   <li>A crash between the send and the mark sends the row again next time:
 *       the same bytes, a repeat that order-service drops.</li>
 * </ul>
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxRepository outbox;
    private final ResultPublisher publisher;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, ResultPublisher publisher, OutboxProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = properties.relay().batchSize();
    }

    /** One pass. False if a row could not be published; the caller decides when to try again. */
    public boolean publishPending() {
        List<OutboxEvent> batch = outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING, Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event.getTopic(), event.getMessageKey(), event.getPayload());
            } catch (RuntimeException failed) {
                log.warn("Could not publish outbox row {} ({}, order {}): {}",
                        event.getId(), event.getTopic(), event.getMessageKey(), failed.getMessage());
                outbox.recordFailure(event.getId(), truncated(failed.toString()));
                return false;
            }
            outbox.markSent(event.getId(), Instant.now());
        }
        return true;
    }

    private static String truncated(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
