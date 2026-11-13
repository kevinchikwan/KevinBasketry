package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutRefusedException.IdempotencyKeyReused;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.config.FingerprintKey;
import com.basketry.order.config.InternalApiKey;
import com.basketry.order.downstream.CatalogClient;
import com.basketry.order.downstream.IdentityClient;
import com.basketry.order.downstream.MerchantClient;
import com.basketry.order.order.CustomerOrderRepository;
import com.basketry.order.order.OrderPlacement;
import com.basketry.order.outbox.OrderOutbox;
import com.basketry.order.support.OrderServiceTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two concurrent first requests with one Idempotency-Key (DESIGN.md Section 2,
 * order-service): both pass the lookup, the second insert breaks
 * uq_customer_order_buyer_key, and a new transaction answers from the winner's
 * order. The losing side is made exact: its lookup misses although the
 * winner's order is already there. postgresTest runs it on PostgreSQL, where
 * the failed insert aborts its transaction.
 */
class IdempotencyRaceTests extends OrderServiceTestBase {

    @Autowired
    private FingerprintKey fingerprintKey;

    @Autowired
    private InternalApiKey internalApiKey;

    @Autowired
    private IdentityClient identity;

    @Autowired
    private CatalogClient catalog;

    @Autowired
    private MerchantClient merchantClient;

    @Autowired
    private CouponValidator coupons;

    @Autowired
    private OrderPlacement placement;

    @Autowired
    private OrderOutbox orderOutbox;

    @Autowired
    private Clock clock;

    @BeforeEach
    void givenACart() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
    }

    /** A CheckoutService whose first idempotency lookup misses, as the losing side of a race. */
    private CheckoutService losingSide() {
        AtomicBoolean missed = new AtomicBoolean();
        CustomerOrderRepository firstLookupMisses = (CustomerOrderRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CustomerOrderRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByBuyerAccountIdAndIdempotencyKey")
                            && missed.compareAndSet(false, true)) {
                        return Optional.empty();
                    }
                    try {
                        return method.invoke(orders, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new CheckoutService(fingerprintKey, internalApiKey, identity, catalog, merchantClient, coupons,
                firstLookupMisses, charges, lines, placement, orderOutbox, transactionManager, clock,
                Duration.ofMinutes(2));
    }

    /**
     * The loser answers the winner's order, and only one order exists.
     * Mutation: no recovery (the unique-key failure propagates).
     */
    @Test
    void theLoserAnswersTheWinnersOrder() {
        CheckoutResult winner = checkout(command("key-0001", "5.00", 10, 2));

        CheckoutResult loser = losingSide().checkout(command("key-0001", "5.00", 10, 2), buyer());

        assertThat(loser.created()).isFalse();
        assertThat(loser.order().getId()).isEqualTo(winner.order().getId());
        assertThat(rowCounts()).containsExactly(1, 1, 1, 2);
    }

    /** The loser with another body is IDEMPOTENCY_KEY_REUSED. Mutation: the recovery skips the fingerprint check. */
    @Test
    void theLoserWithAnotherBodyIsRefused() {
        checkout(command("key-0001", "5.00", 10, 2));

        assertThat(catchThrowableOfType(IdempotencyKeyReused.class,
                () -> losingSide().checkout(command("key-0001", "2.50", 10, 1), buyer()))).isNotNull();
        assertThat(rowCounts()).containsExactly(1, 1, 1, 2);
    }
}
