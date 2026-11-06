package com.basketry.merchant.outbox;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Publishes unsent outbox rows in id order (DESIGN.md Section 5). Each row is
 * sent and acknowledged, then marked in its own short transaction, so no
 * database transaction stays open during a send.
 *
 * <ul>
 *   <li>A failure stops the pass, so a later event of the same merchant cannot
 *       overtake the one that failed. The row stays PENDING.</li>
 *   <li>A crash between the send and the mark sends the row again next time:
 *       the same bytes, dropped by consumers through {@code version}.</li>
 * </ul>
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, OutboxProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = properties.relay().batchSize();
    }

    /** One pass. False if a row could not be published; the caller decides when to try again. */
    public boolean publishPending() {
        List<OutboxEvent> batch = outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING, Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(Long.toString(event.getMerchantId()), event.getPayload());
            } catch (RuntimeException failed) {
                log.warn("Could not publish outbox row {} (merchant {}, version {}): {}",
                        event.getId(), event.getMerchantId(), event.getVersion(), failed.getMessage());
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
