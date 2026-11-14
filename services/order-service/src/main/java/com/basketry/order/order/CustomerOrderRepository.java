package com.basketry.order.order;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.jpa.SpecHints;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    /**
     * How long the order's row lock waits for another transaction before it
     * fails (Postgres waits forever by default): a database error, which the
     * result listener retries (DESIGN.md Section 2, order-service, who decides).
     */
    String LOCK_TIMEOUT_MILLIS = "5000";

    /** The checkout idempotency lookup: unique together (uq_customer_order_buyer_key). */
    Optional<CustomerOrder> findByBuyerAccountIdAndIdempotencyKey(long buyerAccountId, String idempotencyKey);

    Optional<CustomerOrder> findByOrderReference(String orderReference);

    /**
     * Reads and locks the order, first thing in every transaction that applies a
     * result or a timer step: the results of one order are handled one at a
     * time, so exactly one of them decides.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_LOCK_TIMEOUT, value = LOCK_TIMEOUT_MILLIS))
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findByIdForUpdate(long id);

    /** Orders past their deadline with a leg still REQUESTED: the timer's first job. */
    @Query("select o.id from CustomerOrder o where o.status in (com.basketry.order.order.OrderStatus.PENDING,"
            + " com.basketry.order.order.OrderStatus.FAILING) and o.deadlineAt <= :now and ("
            + " exists (select c from MerchantCharge c where c.orderId = o.id"
            + " and c.status = com.basketry.order.order.ChargeStatus.REQUESTED)"
            + " or exists (select l from OrderLine l where l.orderId = o.id"
            + " and l.status = com.basketry.order.order.LineStatus.REQUESTED)) order by o.id")
    List<Long> findIdsPastDeadline(Instant now, Limit limit);

    /** Orders with a refund due again: REFUND_REQUESTED and not confirmed in time. */
    @Query("select distinct c.orderId from MerchantCharge c"
            + " where c.status = com.basketry.order.order.ChargeStatus.REFUND_REQUESTED"
            + " and c.compensationDueAt <= :now order by c.orderId")
    List<Long> findIdsWithRefundDue(Instant now, Limit limit);

    /** Orders with a release due again: RELEASE_REQUESTED or TIMED_OUT, not confirmed in time. */
    @Query("select distinct l.orderId from OrderLine l"
            + " where l.status in (com.basketry.order.order.LineStatus.RELEASE_REQUESTED,"
            + " com.basketry.order.order.LineStatus.TIMED_OUT)"
            + " and l.compensationDueAt <= :now order by l.orderId")
    List<Long> findIdsWithReleaseDue(Instant now, Limit limit);
}
