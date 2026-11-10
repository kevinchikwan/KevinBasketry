package com.basketry.catalog.stockrow;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface StockRowRequestRepository extends JpaRepository<StockRowRequest, Long> {

    /** The relay's read: requests not done yet, in product order. */
    List<StockRowRequest> findByStatusOrderByProductIdAsc(StockRowStatus status, Limit limit);

    /**
     * Only from PENDING, so a final status never changes, and if two instances
     * handle the same request the second mark changes nothing.
     */
    @Transactional
    @Modifying
    @Query("update StockRowRequest r set r.status = :status, r.completedAt = :completedAt, r.lastError = :error"
            + " where r.productId = :productId"
            + " and r.status = com.basketry.catalog.stockrow.StockRowStatus.PENDING")
    int complete(long productId, StockRowStatus status, String error, Instant completedAt);

    /** The request stays PENDING. This is only a record of why it is not done yet. */
    @Transactional
    @Modifying
    @Query("update StockRowRequest r set r.attempts = r.attempts + 1, r.lastError = :error"
            + " where r.productId = :productId")
    int recordFailure(long productId, String error);
}
