package com.basketry.order.outbox;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Publishes unsent outbox rows in id order, each to the topic it names, keyed
 * by orderId (DESIGN.md Section 5), as payment-service's relay. Each row is sent
 * and acknowledged, then marked in its own short transaction, so no database
 * transaction is open during a send. Not {@code @Transactional}: one
 * transaction around a pass would roll back the marks of rows already sent when
 * a later one fails.
 *
 * <ul>
 *   <li>The mark is SENT only from PENDING, and replaces a charge request's
 *       payload with one whose card number is null. A row the timer cancelled
 *       meanwhile stays CANCELLED.</li>
 *   <li>A failure stops the pass, so no later row is sent before the one that
 *       failed. The row stays PENDING, with its card number.</li>
 *   <li>A crash between the send and the mark sends the row again next time:
 *       the same bytes, a repeat the receiver drops.</li>
 *   <li>Ids follow insert order, not commit order; harmless here because every
 *       compensation is written only after what it compensates committed.</li>
 * </ul>
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxRepository outbox;
    private final RequestPublisher publisher;
    private final CardNumberScrub scrub;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, RequestPublisher publisher, CardNumberScrub scrub, Clock clock,
                       OutboxProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.scrub = scrub;
        this.clock = clock;
        this.batchSize = properties.relay().batchSize();
    }

    /** One pass. False if a row could not be published; the caller decides when to try again. */
    public boolean publishPending() {
        List<OutboxEvent> batch = outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING, Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event.getTopic(), event.getMessageKey(), event.getPayload());
            } catch (RuntimeException failed) {
                // Never the payload. Kafka's send errors name the topic and partition, never a value
                // (the card-number leak test sends a failing charge request).
                log.warn("Could not publish outbox row {} ({}, order {}): {}",
                        event.getId(), event.getTopic(), event.getMessageKey(), failed.toString());
                outbox.recordFailure(event.getId(), truncated(failed.toString()));
                return false;
            }
            outbox.markSent(event.getId(), clock.instant(), scrub.scrubbed(event.getPayload()));
        }
        return true;
    }

    private static String truncated(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
