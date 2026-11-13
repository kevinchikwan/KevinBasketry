package com.basketry.order.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    /** The checkout idempotency lookup: unique together (uq_customer_order_buyer_key). */
    Optional<CustomerOrder> findByBuyerAccountIdAndIdempotencyKey(long buyerAccountId, String idempotencyKey);

    Optional<CustomerOrder> findByOrderReference(String orderReference);
}
