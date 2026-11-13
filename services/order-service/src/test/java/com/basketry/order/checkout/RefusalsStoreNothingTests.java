package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutRefusedException.BuyerRefused;
import com.basketry.order.checkout.CheckoutRefusedException.CartNotPurchasable;
import com.basketry.order.checkout.CheckoutRefusedException.IdempotencyKeyReused;
import com.basketry.order.checkout.CheckoutRefusedException.PriceChanged;
import com.basketry.order.checkout.CheckoutRefusedException.Unavailable;
import com.basketry.order.support.FakeServices;
import com.basketry.order.support.OrderServiceTestBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every refusal stores nothing: customer_order, merchant_charge, order_line and
 * outbox_event all keep 0 rows (DESIGN.md Section 2, order-service: "a checkout
 * refused at step 5 stores nothing"), so a retry with the same key runs again.
 * The two key-not-configured 503s are in FingerprintKeyUnsetTests and
 * InternalKeyUnsetTests, which need their own contexts.
 */
class RefusalsStoreNothingTests extends OrderServiceTestBase {

    private static final List<Integer> NOTHING = List.of(0, 0, 0, 0);

    @BeforeEach
    void givenACart() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
    }

    /** Mutation: an order saved before the three calls (a placeholder for the key). */
    @ParameterizedTest
    @ValueSource(strings = {FakeServices.ACCOUNTS, FakeServices.PRODUCTS, FakeServices.MERCHANTS})
    void eachUnavailableServiceStoresNothing(String path) {
        services.answer(path, 503, "{}");

        assertThat(catchThrowableOfType(Unavailable.class, () -> checkout(command("key-0001", "2.50", 10, 1))))
                .isNotNull();
        assertThat(rowCounts()).isEqualTo(NOTHING);
    }

    /** Mutation: as above. */
    @Test
    void aRefusedBuyerStoresNothing() {
        services.givenAccount(BUYER, BUYER_TOKEN, false, null);
        assertThat(catchThrowableOfType(BuyerRefused.class, () -> checkout(command("key-0001", "2.50", 10, 1)))
                .code()).isEqualTo("BUYER_DEACTIVATED");
        assertThat(rowCounts()).isEqualTo(NOTHING);

        services.reset();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        assertThat(catchThrowableOfType(BuyerRefused.class, () -> checkout(command("key-0001", "2.50", 10, 1)))
                .code()).isEqualTo("BUYER_NOT_FOUND");
        assertThat(rowCounts()).isEqualTo(NOTHING);
    }

    /** Mutation: as above. */
    @Test
    void aCartThatCannotBeBoughtStoresNothing() {
        assertThat(catchThrowableOfType(CartNotPurchasable.class,
                () -> checkout(command("key-0001", "2.50", 10, 1, 99, 1)))).isNotNull();
        assertThat(rowCounts()).isEqualTo(NOTHING);
    }

    /** Mutation: the order saved before the price check. */
    @Test
    void aChangedPriceStoresNothing() {
        assertThat(catchThrowableOfType(PriceChanged.class, () -> checkout(command("key-0001", "2.49", 10, 1))))
                .isNotNull();
        assertThat(rowCounts()).isEqualTo(NOTHING);
    }

    /**
     * A body rule refuses before the service runs. Mutation: CheckoutCommand
     * without its throw (a quantity of 1,001 then becomes an order).
     */
    @Test
    void aBrokenBodyRuleStoresNothing() {
        assertThat(catchThrowableOfType(InvalidCheckoutException.class,
                () -> checkout(new CheckoutCommand("key-0001", List.of(new CheckoutLine(10L, 1001L)), null, CARD,
                        new BigDecimal("2502.50"))))).isNotNull();
        assertThat(rowCounts()).isEqualTo(NOTHING);
    }

    /**
     * The same key with another body adds nothing to the first order's rows.
     * Mutation: the fingerprint not compared (the replay answered instead).
     */
    @Test
    void aReusedKeyAddsNothing() {
        checkout(command("key-0001", "2.50", 10, 1));
        List<Integer> afterFirst = rowCounts();

        assertThat(catchThrowableOfType(IdempotencyKeyReused.class, () -> checkout(command("key-0001", "5.00", 10, 2))))
                .isNotNull();
        assertThat(rowCounts()).isEqualTo(afterFirst).isEqualTo(List.of(1, 1, 1, 2));
    }
}
