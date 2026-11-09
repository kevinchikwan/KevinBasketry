package com.basketry.inventory.stock;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface StockReservationRepository extends JpaRepository<StockReservation, ReservationKey> {

    /** The idempotency lookup of both reserve and release. */
    Optional<StockReservation> findByOrderIdAndProductId(long orderId, long productId);

    /**
     * Only from RESERVED, in one statement, so two overlapping releases move the
     * row once: the second updates nothing and gives nothing back. Runs in the
     * caller's transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("update StockReservation r set r.status = com.basketry.inventory.stock.ReservationStatus.RELEASED,"
            + " r.releasedAt = :now where r.orderId = :orderId and r.productId = :productId"
            + " and r.status = com.basketry.inventory.stock.ReservationStatus.RESERVED")
    int markReleased(long orderId, long productId, Instant now);
}
