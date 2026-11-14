package com.basketry.order.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, OrderLine.Key> {

    List<OrderLine> findByOrderIdOrderByProductIdAsc(long orderId);

    /** For a page of summaries: each order's number of lines, as [orderId, count]. */
    @Query("select l.orderId, count(l) from OrderLine l where l.orderId in :orderIds group by l.orderId")
    List<Object[]> countLinesByOrder(Collection<Long> orderIds);

    /** For a merchant's page of summaries: each order's number of lines of this merchant. */
    @Query("select l.orderId, count(l) from OrderLine l where l.orderId in :orderIds and l.merchantId = :merchantId"
            + " group by l.orderId")
    List<Object[]> countLinesByOrderOf(Collection<Long> orderIds, long merchantId);
}
