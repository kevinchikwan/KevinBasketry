package com.basketry.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.checkout.CheckoutCommand;
import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.support.OrderServiceTestBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The largest valid checkout fits every column (DESIGN.md Section 2,
 * order-service, amounts): 100 lines of 1,000 units at 99,999.99, one merchant
 * at a 100.00% rate, 100-character names and a 64-character key. A database
 * error caused by a valid request would be a 500.
 */
class ColumnLimitsTests extends OrderServiceTestBase {

    /** Mutations: amount or commission NUMERIC(11, 2); product_name VARCHAR(99); idempotency_key VARCHAR(63). */
    @Test
    void theLargestCheckoutIsStored() {
        givenBuyer();
        givenMerchant(MERCHANT, "100.00");
        List<CheckoutLine> cart = new ArrayList<>();
        for (long product = 1; product <= 100; product++) {
            services.givenProduct(product, MERCHANT, "N".repeat(100), "99999.99", true, true, "CREATED");
            cart.add(new CheckoutLine(product, 1000L));
        }
        CheckoutCommand command = new CheckoutCommand("k".repeat(64), cart, null, "4000000000000000002",
                new BigDecimal("9999999000.00"));

        CheckoutResult result = checkout(command);

        MerchantCharge charge = charges.findByOrderIdOrderByMerchantIdAsc(result.order().getId()).getFirst();
        assertThat(charge.getAmount()).isEqualTo(new BigDecimal("9999999000.00"));
        assertThat(charge.getCommission()).isEqualTo(new BigDecimal("9999999000.00"));
        assertThat(orders.findById(result.order().getId()).orElseThrow().getTotalAmount())
                .isEqualTo(new BigDecimal("9999999000.00"));
        assertThat(lines.findByOrderIdOrderByProductIdAsc(result.order().getId())).hasSize(100)
                .allSatisfy(line -> assertThat(line.getProductName()).hasSize(100));
        assertThat(outboxRows()).hasSize(101);
    }
}
