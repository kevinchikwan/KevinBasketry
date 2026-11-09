package com.basketry.inventory.stock;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Every change of a quantity is one conditional statement, so concurrent
 * changes never overwrite each other and the condition is checked against the
 * row as it is when the statement runs. The modifying statements run in the
 * caller's transaction.
 */
public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    /** The PostgreSQL gate: takes the stock only if there is enough. 1 if taken, 0 if not (or no row). */
    @Modifying(clearAutomatically = true)
    @Query("update StockLevel s set s.quantity = s.quantity - :quantity, s.updatedAt = :now"
            + " where s.productId = :productId and s.quantity >= :quantity")
    int take(long productId, long quantity, Instant now);

    /** A release. No upper bound: what was taken always goes back. */
    @Modifying(clearAutomatically = true)
    @Query("update StockLevel s set s.quantity = s.quantity + :quantity, s.updatedAt = :now"
            + " where s.productId = :productId")
    int giveBack(long productId, long quantity, Instant now);

    /** An adjustment upwards, only if the result stays within {@code max}. */
    @Modifying(clearAutomatically = true)
    @Query("update StockLevel s set s.quantity = s.quantity + :amount, s.updatedAt = :now"
            + " where s.productId = :productId and s.quantity + :amount <= :max")
    int increase(long productId, long amount, long max, Instant now);

    /** An adjustment downwards, only if the result stays at zero or more. Allowed above the maximum. */
    @Modifying(clearAutomatically = true)
    @Query("update StockLevel s set s.quantity = s.quantity - :amount, s.updatedAt = :now"
            + " where s.productId = :productId and s.quantity >= :amount")
    int decrease(long productId, long amount, Instant now);

    /** A plain read, no lock: the confirming read and the counter load. */
    @Query("select s.quantity from StockLevel s where s.productId = :productId")
    Optional<Long> findQuantity(long productId);

    /** GET /stock for one merchant. */
    Page<StockLevel> findByMerchantId(long merchantId, Pageable pageable);

    /** GET /internal/stock. */
    List<StockLevel> findByProductIdInOrderByProductIdAsc(Collection<Long> productIds);

    /** Reconciliation's keyset paging, in product order. */
    @Query("select new com.basketry.inventory.stock.StockLevelRepository$ProductQuantity(s.productId, s.quantity)"
            + " from StockLevel s where s.productId > :after order by s.productId")
    List<ProductQuantity> findPageAfter(long after, Limit limit);

    record ProductQuantity(long productId, long quantity) {
    }
}
