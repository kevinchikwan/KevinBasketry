package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutRefusedException.BuyerRefused;
import com.basketry.order.checkout.CheckoutRefusedException.CartNotPurchasable;
import com.basketry.order.checkout.CheckoutRefusedException.CurrentPrice;
import com.basketry.order.checkout.CheckoutRefusedException.IdempotencyKeyReused;
import com.basketry.order.checkout.CheckoutRefusedException.PriceChanged;
import com.basketry.order.checkout.CheckoutRefusedException.RefusedLine;
import com.basketry.order.checkout.CheckoutRefusedException.Unavailable;
import com.basketry.order.checkout.CheckoutService.Buyer;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderReferences;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.outbox.OutboxStatus;
import com.basketry.order.support.FakeServices;
import com.basketry.order.support.OrderServiceTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Checkout end to end inside the service (DESIGN.md Section 2, order-service,
 * checkout in order): the three calls over real HTTP to {@link FakeServices},
 * the rules, the Idempotency-Key and the one transaction.
 */
class CheckoutServiceTests extends OrderServiceTestBase {

    /** Two merchants, three lines: 2 x 2.50 + 1 x 4.00 for merchant 3, 3 x 1.25 for merchant 4. */
    private void givenTwoMerchantCart() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenProduct(11, MERCHANT, "4.00");
        givenProduct(20, OTHER_MERCHANT, "1.25");
        givenMerchant(MERCHANT, "5.00");
        givenMerchant(OTHER_MERCHANT, "12.50");
    }

    private static final String TOTAL = "12.75";

    private CheckoutResult checkoutTwoMerchantCart(String key) {
        return checkout(command(key, TOTAL, 10, 2, 20, 3, 11, 1));
    }

    /**
     * A new order: PENDING, its reference, total and deadline, one charge leg per
     * merchant with the rate snapshotted and the commission on it, one line per
     * cart line with catalog's name and price, all REQUESTED. Mutations: the
     * rate not snapshotted (0.00 stored); deadlineAt = createdAt; the product
     * name or price taken from another line.
     */
    @Test
    void aNewOrderIsWrittenWithItsLegs() {
        givenTwoMerchantCart();

        CheckoutResult result = checkoutTwoMerchantCart("key-0001");

        assertThat(result.created()).isTrue();
        CustomerOrder order = orders.findById(result.order().getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getOrderReference()).matches(OrderReferences.FORMAT);
        assertThat(order.getBuyerAccountId()).isEqualTo(BUYER);
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("12.75"));
        assertThat(order.getCouponCode()).isNull();
        assertThat(Duration.between(order.getCreatedAt(), order.getDeadlineAt())).isEqualTo(Duration.ofMinutes(2));
        assertThat(order.getDecidedAt()).isNull();
        assertThat(order.getRequestFingerprint()).matches("[0-9a-f]{64}");

        List<MerchantCharge> legs = charges.findByOrderIdOrderByMerchantIdAsc(order.getId());
        assertThat(legs).extracting(MerchantCharge::getMerchantId).containsExactly(MERCHANT, OTHER_MERCHANT);
        assertThat(legs).extracting(MerchantCharge::getAmount)
                .containsExactly(new BigDecimal("9.00"), new BigDecimal("3.75"));
        assertThat(legs).extracting(MerchantCharge::getCommissionRate)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("12.50"));
        // 9.00 x 5% = 0.45; 3.75 x 12.5% = 0.46875, so 0.47.
        assertThat(legs).extracting(MerchantCharge::getCommission)
                .containsExactly(new BigDecimal("0.45"), new BigDecimal("0.47"));
        assertThat(legs).extracting(MerchantCharge::getDiscount).containsOnly(new BigDecimal("0.00"));
        assertThat(legs).extracting(MerchantCharge::getStatus).containsOnly(ChargeStatus.REQUESTED);

        List<OrderLine> cart = lines.findByOrderIdOrderByProductIdAsc(order.getId());
        assertThat(cart).extracting(OrderLine::getProductId).containsExactly(10L, 11L, 20L);
        assertThat(cart).extracting(OrderLine::getProductName)
                .containsExactly("Product 10", "Product 11", "Product 20");
        assertThat(cart).extracting(OrderLine::getUnitPrice)
                .containsExactly(new BigDecimal("2.50"), new BigDecimal("4.00"), new BigDecimal("1.25"));
        assertThat(cart).extracting(OrderLine::getQuantity).containsExactly(2, 1, 3);
        assertThat(cart).extracting(OrderLine::getLineAmount)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("4.00"), new BigDecimal("3.75"));
        assertThat(cart).extracting(OrderLine::getMerchantId).containsExactly(MERCHANT, MERCHANT, OTHER_MERCHANT);
        assertThat(cart).extracting(OrderLine::getStatus).containsOnly(LineStatus.REQUESTED);
    }

    /**
     * The order's requests, in the same transaction: one charge per merchant
     * (with the card number and the leg's amount) and one reserve per line, all
     * PENDING and keyed by the orderId. Mutations: the reserves left out; the
     * charge's amount from the subtotal of the first merchant for every merchant.
     */
    @Test
    void theRequestsAreInTheOutbox() {
        givenTwoMerchantCart();

        long orderId = checkoutTwoMerchantCart("key-0001").order().getId();

        List<OutboxEvent> rows = outboxRows();
        assertThat(rows).extracting(OutboxEvent::getTopic).containsExactly(
                OrderRequests.CHARGE_TOPIC, OrderRequests.CHARGE_TOPIC,
                OrderRequests.RESERVE_TOPIC, OrderRequests.RESERVE_TOPIC, OrderRequests.RESERVE_TOPIC);
        assertThat(rows).extracting(OutboxEvent::getMessageKey).containsOnly(Long.toString(orderId));
        assertThat(rows).extracting(OutboxEvent::getStatus).containsOnly(OutboxStatus.PENDING);
        assertThat(rows.get(0).getPayload()).contains("\"merchantId\":3,\"amount\":9.00,\"cardNumber\":\"" + CARD + "\"");
        assertThat(rows.get(1).getPayload()).contains("\"merchantId\":4,\"amount\":3.75,\"cardNumber\":\"" + CARD + "\"");
        assertThat(rows.subList(2, 5)).extracting(this::payloadOf).extracting(payload -> payload.get("productId"))
                .containsExactly(10, 20, 11);
        assertThat(payloadOf(rows.get(3))).containsEntry("quantity", 3).containsEntry("orderId", (int) orderId);
    }

    /**
     * identity-service gets the buyer's own token; catalog-service and
     * merchant-service each get one call with every id and the internal key.
     * Mutations: the token not forwarded (identity answers 401: 503); one merchant
     * call per merchant; the key not sent.
     */
    @Test
    void eachServiceIsCalledOnceWithTheRightCredentials() {
        givenTwoMerchantCart();

        checkoutTwoMerchantCart("key-0001");

        assertThat(services.calls(FakeServices.ACCOUNTS)).singleElement().satisfies(call -> {
            assertThat(call.uri()).isEqualTo("/accounts/" + BUYER);
            assertThat(call.authorization()).isEqualTo("Bearer " + BUYER_TOKEN);
        });
        assertThat(services.calls(FakeServices.PRODUCTS)).singleElement().satisfies(call -> {
            assertThat(call.uri()).isEqualTo("/internal/products?ids=10,11,20");
            assertThat(call.apiKey()).isEqualTo(API_KEY);
        });
        assertThat(services.calls(FakeServices.MERCHANTS)).singleElement().satisfies(call -> {
            assertThat(call.uri()).isEqualTo("/internal/merchants?ids=3,4");
            assertThat(call.apiKey()).isEqualTo(API_KEY);
        });
    }

    /**
     * The same key and the same body: the same order as it stands, no call to
     * any service, nothing new written, even though prices changed meanwhile.
     * The lines in another order and 12.750 are the same body. Mutation: the
     * idempotency lookup skipped (a second order).
     */
    @Test
    void aReplayAnswersTheSameOrderAndCallsNothing() {
        givenTwoMerchantCart();
        CheckoutResult first = checkoutTwoMerchantCart("key-0001");
        int callsBefore = services.calls().size();
        givenProduct(10, MERCHANT, "3.00");

        CheckoutResult replay = checkout(command("key-0001", "12.750", 11, 1, 10, 2, 20, 3));

        assertThat(replay.created()).isFalse();
        assertThat(replay.order().getId()).isEqualTo(first.order().getId());
        assertThat(replay.charges()).hasSize(2);
        assertThat(replay.lines()).hasSize(3);
        assertThat(services.calls()).hasSize(callsBefore);
        assertThat(rowCounts()).containsExactly(1, 2, 3, 5);
    }

    /** The same key with another body is refused, and nothing changes. Mutation: the fingerprint not compared. */
    @Test
    void theSameKeyWithAnotherBodyIsRefused() {
        givenTwoMerchantCart();
        checkoutTwoMerchantCart("key-0001");

        assertThat(catchThrowableOfType(IdempotencyKeyReused.class,
                () -> checkout(command("key-0001", "5.00", 10, 2)))).isNotNull();
        assertThat(catchThrowableOfType(IdempotencyKeyReused.class,
                () -> checkout(command("key-0001", "4000000000000010", TOTAL, 10, 2, 20, 3, 11, 1)))).isNotNull();
        assertThat(rowCounts()).containsExactly(1, 2, 3, 5);
    }

    /** A key belongs to its buyer. Mutation: the lookup by key alone (another buyer's order answered). */
    @Test
    void anotherBuyerWithTheSameKeyGetsItsOwnOrder() {
        givenTwoMerchantCart();
        services.givenAccount(13, "buyer-13-token", true, null);
        long first = checkoutTwoMerchantCart("key-0001").order().getId();

        CheckoutResult other = checkoutService.checkout(command("key-0001", TOTAL, 10, 2, 20, 3, 11, 1),
                new Buyer(13, "buyer-13-token"));

        assertThat(other.created()).isTrue();
        assertThat(other.order().getId()).isNotEqualTo(first);
    }

    /** Mutation: the buyerActive check removed. Nothing is stored and catalog is never asked. */
    @Test
    void aDeactivatedBuyerIsRefusedBeforeAnythingElse() {
        givenTwoMerchantCart();
        services.givenAccount(BUYER, BUYER_TOKEN, false, null);

        BuyerRefused refused = catchThrowableOfType(BuyerRefused.class, () -> checkoutTwoMerchantCart("key-0001"));

        assertThat(refused.code()).isEqualTo("BUYER_DEACTIVATED");
        assertThat(services.calls(FakeServices.PRODUCTS)).isEmpty();
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }

    /** identity-service does not know the account (data loss). Mutation: a 404 taken as IDENTITY_UNAVAILABLE. */
    @Test
    void anUnknownAccountIsBuyerNotFound() {
        givenTwoMerchantCart();
        services.reset();
        givenProduct(10, MERCHANT, "2.50");

        BuyerRefused refused = catchThrowableOfType(BuyerRefused.class, () -> checkout(command("key-0001", "5.00", 10, 2)));

        assertThat(refused.code()).isEqualTo("BUYER_NOT_FOUND");
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }

    /**
     * Every refused line in one answer, and nothing stored. OWN_PRODUCT comes
     * from identity-service's live link, not from a token claim. Mutation: the
     * buyer's merchantId not passed to the rules.
     */
    @Test
    void aCartWithLinesThatCannotBeBoughtIsRefusedWhole() {
        givenTwoMerchantCart();
        services.givenAccount(BUYER, BUYER_TOKEN, true, OTHER_MERCHANT);
        services.givenProduct(11, MERCHANT, "Product 11", "4.00", false, true, "CREATED");

        CartNotPurchasable refused = catchThrowableOfType(CartNotPurchasable.class,
                () -> checkout(command("key-0001", TOTAL, 10, 2, 20, 3, 11, 1, 99, 1)));

        assertThat(refused.lines()).containsExactly(
                new RefusedLine(20, RefusalReason.OWN_PRODUCT),
                new RefusedLine(11, RefusalReason.INACTIVE),
                new RefusedLine(99, RefusalReason.NOT_FOUND));
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }

    /** merchant-service's live flag counts although catalog still says active. Mutation: its flag ignored. */
    @Test
    void merchantServiceSeesADeactivationCatalogHasNotYet() {
        givenTwoMerchantCart();
        services.givenMerchant(OTHER_MERCHANT, false, "12.50");

        CartNotPurchasable refused = catchThrowableOfType(CartNotPurchasable.class,
                () -> checkoutTwoMerchantCart("key-0001"));

        assertThat(refused.lines()).containsExactly(new RefusedLine(20, RefusalReason.MERCHANT_INACTIVE));
    }

    /**
     * The server's total must equal the shown one, numerically: 12.7 is not
     * 12.75, but 12.750 is. The refusal names every line's current price and
     * the total. Mutation: the price check removed.
     */
    @Test
    void aChangedTotalIsRefusedWithTheCurrentPrices() {
        givenTwoMerchantCart();

        PriceChanged refused = catchThrowableOfType(PriceChanged.class,
                () -> checkout(command("key-0001", "12.70", 10, 2, 20, 3, 11, 1)));

        assertThat(refused.total()).isEqualTo(new BigDecimal("12.75"));
        assertThat(refused.lines()).containsExactly(new CurrentPrice(10, new BigDecimal("2.50")),
                new CurrentPrice(20, new BigDecimal("1.25")), new CurrentPrice(11, new BigDecimal("4.00")));
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);

        assertThat(checkout(command("key-0001", "12.750", 10, 2, 20, 3, 11, 1)).created()).isTrue();
    }

    /**
     * A refused checkout stores nothing, so the same key runs again once the
     * cause is gone. Mutation: the key recorded before the checks (the retry
     * then answers IDEMPOTENCY_KEY_REUSED or the refusal again).
     */
    @Test
    void aRefusedCheckoutCanBeRetriedWithTheSameKey() {
        givenTwoMerchantCart();
        services.answer(FakeServices.PRODUCTS, 500, "{}");
        assertThat(catchThrowableOfType(Unavailable.class, () -> checkoutTwoMerchantCart("key-0001")).code())
                .isEqualTo("CATALOG_UNAVAILABLE");
        services.reset();
        givenTwoMerchantCart();

        assertThat(checkoutTwoMerchantCart("key-0001").created()).isTrue();
    }

    /**
     * Each service down is its own 503 code, and nothing is stored. Mutation:
     * every DownstreamUnavailableException mapped to one code.
     */
    @Test
    void eachUnavailableServiceHasItsOwnCode() {
        givenTwoMerchantCart();
        Map<String, String> codes = Map.of(FakeServices.ACCOUNTS, "IDENTITY_UNAVAILABLE",
                FakeServices.PRODUCTS, "CATALOG_UNAVAILABLE", FakeServices.MERCHANTS, "MERCHANT_UNAVAILABLE");
        codes.forEach((path, code) -> {
            services.reset();
            givenTwoMerchantCart();
            services.answer(path, 503, "{}");

            assertThat(catchThrowableOfType(Unavailable.class, () -> checkoutTwoMerchantCart("key-0001")).code())
                    .as(path).isEqualTo(code);
        });
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }

    /**
     * The order, its legs and its outbox rows commit together: when the outbox
     * write fails, nothing is stored. Mutation: the order placed in its own
     * transaction ({@code OrderPlacement.place} as {@code REQUIRES_NEW}), so it
     * commits before the outbox fails.
     */
    @Test
    void theOrderAndItsRequestsCommitTogether() {
        givenTwoMerchantCart();
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT ck_test_no_reserves"
                + " CHECK (topic <> 'order.stock-reserve.requested')");
        try {
            assertThat(catchThrowableOfType(RuntimeException.class, () -> checkoutTwoMerchantCart("key-0001")))
                    .isNotNull();
            assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT ck_test_no_reserves");
        }
    }
}
