package com.basketry.order.order;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a new order with its charge legs and lines, all REQUESTED. The
 * caller's transaction must also write the order's requests to the outbox, so
 * the order and its requests commit together or not at all (DESIGN.md Section
 * 2, order-service, checkout step 6).
 */
@Service
public class OrderPlacement {

    private final CustomerOrderRepository orders;
    private final MerchantChargeRepository charges;
    private final OrderLineRepository lines;

    public OrderPlacement(CustomerOrderRepository orders, MerchantChargeRepository charges,
                          OrderLineRepository lines) {
        this.orders = orders;
        this.charges = charges;
        this.lines = lines;
    }

    /** Fails if no transaction is open. The order's id is taken from its sequence here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Placed place(CustomerOrder order, Amounts amounts) {
        CustomerOrder saved = orders.save(order);
        long orderId = saved.getId();
        List<MerchantCharge> savedCharges = amounts.charges().stream()
                .map(charge -> charges.save(new MerchantCharge(orderId, charge)))
                .toList();
        List<OrderLine> savedLines = amounts.lines().stream()
                .map(line -> lines.save(new OrderLine(orderId, line)))
                .toList();
        // Inserted now, so a broken unique key fails here, inside the caller's error handling.
        orders.flush();
        return new Placed(saved, savedCharges, savedLines);
    }

    /** The order as written, with its legs in the order the cart named them. */
    public record Placed(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines) {
    }
}
