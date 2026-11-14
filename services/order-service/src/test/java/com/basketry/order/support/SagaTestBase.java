package com.basketry.order.support;

import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.outbox.OrderRequests;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.saga.SagaResult;
import com.basketry.order.saga.SagaService;
import com.basketry.order.saga.SagaTimer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * The coordinator's tests: the real application with a clock the test moves,
 * results applied through {@link SagaService} and timer passes run through
 * {@link SagaTimer}, both as the listeners and the schedule would. No startup
 * grace here (SagaGraceTests has it).
 *
 * <p>The standard order: merchant 3 sells product 10 (1 x 2.50), merchant 4
 * sells product 20 (2 x 1.25): two charge legs, two lines, total 5.00.
 */
@Import(SagaTestBase.TestClock.class)
@TestPropertySource(properties = "basketry.order.startup-grace=0s")
public abstract class SagaTestBase extends OrderServiceTestBase {

    public static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    protected static final Duration DEADLINE = Duration.ofMinutes(2);
    protected static final Duration RESEND = Duration.ofMinutes(1);
    protected static final long PRODUCT_A = 10;
    protected static final long PRODUCT_B = 20;

    private static final AtomicInteger KEYS = new AtomicInteger();

    @TestConfiguration
    public static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected SagaService saga;

    @Autowired
    protected SagaTimer timer;

    @BeforeEach
    void startAtT0() {
        clock.set(T0);
    }

    /** The standard order, PENDING, with its legs REQUESTED. */
    protected long order() {
        givenBuyer();
        givenProduct(PRODUCT_A, MERCHANT, "2.50");
        givenProduct(PRODUCT_B, OTHER_MERCHANT, "1.25");
        givenMerchant(MERCHANT, "5.00");
        givenMerchant(OTHER_MERCHANT, "5.00");
        return checkout(command("key-" + String.format("%04d", KEYS.incrementAndGet()), "5.00",
                PRODUCT_A, 1, PRODUCT_B, 2)).order().getId();
    }

    /** The order's checkout requests as if the relay had sent them: only later rows stay PENDING. */
    protected void checkoutRequestsSent() {
        jdbc.update("UPDATE outbox_event SET status = 'SENT', sent_at = CURRENT_TIMESTAMP WHERE status = 'PENDING'");
    }

    protected static String ref(long merchantId) {
        return "TXN-" + String.format("%032x", merchantId);
    }

    protected void charged(long orderId, long merchantId) {
        saga.apply(new SagaResult.Charged(orderId, merchantId, ref(merchantId)));
    }

    protected void declined(long orderId, long merchantId, String code) {
        saga.apply(new SagaResult.Declined(orderId, merchantId, code));
    }

    protected void refunded(long orderId, long merchantId) {
        saga.apply(new SagaResult.Refunded(orderId, merchantId));
    }

    protected void reserved(long orderId, long productId) {
        saga.apply(new SagaResult.Reserved(orderId, productId));
    }

    protected void reservationFailed(long orderId, long productId, String code) {
        saga.apply(new SagaResult.ReservationFailed(orderId, productId, code));
    }

    protected void released(long orderId, long productId) {
        saga.apply(new SagaResult.Released(orderId, productId));
    }

    protected CustomerOrder orderOf(long orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    protected MerchantCharge chargeOf(long orderId, long merchantId) {
        return charges.findById(new MerchantCharge.Key(orderId, merchantId)).orElseThrow();
    }

    protected OrderLine lineOf(long orderId, long productId) {
        return lines.findById(new OrderLine.Key(orderId, productId)).orElseThrow();
    }

    /** The order's outbox rows on {@code topic}, oldest first. */
    protected List<OutboxEvent> rows(long orderId, String topic) {
        return outboxRows().stream()
                .filter(row -> row.getMessageKey().equals(Long.toString(orderId)) && row.getTopic().equals(topic))
                .toList();
    }

    protected List<OutboxEvent> refunds(long orderId) {
        return rows(orderId, OrderRequests.REFUND_TOPIC);
    }

    protected List<OutboxEvent> releases(long orderId) {
        return rows(orderId, OrderRequests.RELEASE_TOPIC);
    }

    /** Moves the clock and runs one timer pass. */
    protected int passAfter(Duration duration) {
        clock.advance(duration);
        return timer.pass();
    }
}
