package com.basketry.order.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, OrderLine.Key> {

    List<OrderLine> findByOrderIdOrderByProductIdAsc(long orderId);
}
