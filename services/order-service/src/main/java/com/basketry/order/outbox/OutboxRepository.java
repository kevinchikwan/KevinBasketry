package com.basketry.order.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findAllByOrderByIdAsc();

    /** The relay's read: unsent rows, oldest first. CANCELLED rows are never read. */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Limit limit);

    /** One order's rows of some topics in one status, oldest first: the cancel's read. */
    List<OutboxEvent> findByMessageKeyAndStatusAndTopicInOrderByIdAsc(String messageKey, OutboxStatus status,
                                                                    Collection<String> topics);

    /**
     * Only from PENDING, and with the payload whose card number is removed: if
     * two instances publish the same row, or the timer cancelled it meanwhile,
     * this changes nothing.
     */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.status = com.basketry.order.outbox.OutboxStatus.SENT, e.sentAt = :sentAt,"
            + " e.payload = :scrubbedPayload"
            + " where e.id = :id and e.status = com.basketry.order.outbox.OutboxStatus.PENDING")
    int markSent(long id, Instant sentAt, String scrubbedPayload);

    /**
     * Only from PENDING, and with the payload whose card number is removed. Runs
     * in the caller's transaction (the timer's), so it commits with the order's
     * change. A row the relay marked SENT first is left as it is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("update OutboxEvent e set e.status = com.basketry.order.outbox.OutboxStatus.CANCELLED,"
            + " e.payload = :scrubbedPayload"
            + " where e.id = :id and e.status = com.basketry.order.outbox.OutboxStatus.PENDING")
    int markCancelled(long id, String scrubbedPayload);

    /** The row stays PENDING. This is only a record of why it has not gone yet. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id = :id")
    int recordFailure(long id, String error);
}
