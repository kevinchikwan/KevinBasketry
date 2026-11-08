package com.basketry.payment.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findAllByOrderByIdAsc();

    /** The relay's read: unsent rows, oldest first. */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Limit limit);

    /** Only from PENDING: if two instances publish the same row, the second mark changes nothing. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.status = com.basketry.payment.outbox.OutboxStatus.SENT, e.sentAt = :sentAt"
            + " where e.id = :id and e.status = com.basketry.payment.outbox.OutboxStatus.PENDING")
    int markSent(long id, Instant sentAt);

    /** The row stays PENDING. This is only a record of why it has not gone yet. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id = :id")
    int recordFailure(long id, String error);
}
