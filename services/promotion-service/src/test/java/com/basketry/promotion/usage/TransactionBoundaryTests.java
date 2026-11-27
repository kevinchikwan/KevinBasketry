package com.basketry.promotion.usage;

import static com.basketry.promotion.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * No transaction is open when the listener calls CouponUsageService (DESIGN.md
 * Section 5, promotion-service): the service opens its own, so a lost race on
 * the primary key is caught after its transaction has ended. An outer one
 * would be joined instead, and on PostgreSQL left aborted by the failed insert.
 * CouponUsageService has no proxy, so its spy sees the listener's own state.
 */
@EmbeddedKafka(
        topics = OrderSucceededTopic.NAME,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = "basketry.coupon-usage.consumer.enabled=true")
class TransactionBoundaryTests extends PromotionServiceTestBase {

    @Autowired
    private KafkaTemplate<byte[], byte[]> kafka;

    @Autowired
    private CouponRepository coupons;

    @MockitoSpyBean
    private CouponUsageService spiedUsages;

    /** Whether a transaction was open at each call. */
    private final List<Boolean> seen = new CopyOnWriteArrayList<>();

    /** Calls that have returned: the test waits for these, not for {@link #seen}, before it ends. */
    private final AtomicInteger finished = new AtomicInteger();

    private Object record(org.mockito.invocation.InvocationOnMock call) throws Throwable {
        seen.add(TransactionSynchronizationManager.isActualTransactionActive());
        try {
            return call.callRealMethod();
        } finally {
            finished.incrementAndGet();
        }
    }

    /**
     * Mutation: {@code @Transactional} on the listener's method (the service
     * then sees the listener's transaction).
     */
    @Test
    void theListenerCallsTheServiceOutsideAnyTransaction() throws Exception {
        coupons.save(new Coupon(new CouponCode("SPRING10"), CouponScope.MERCHANT, 3L,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), CouponUsageServiceTests.FAR_FUTURE));
        doAnswer(this::record).when(spiedUsages).record(any());

        kafka.send(OrderSucceededTopic.NAME, OrderSucceededKafkaTests.key(201),
                OrderSucceededKafkaTests.event(201, "SPRING10").getBytes(StandardCharsets.UTF_8)).get();
        kafka.send(OrderSucceededTopic.NAME, OrderSucceededKafkaTests.key(202),
                OrderSucceededKafkaTests.event(202, null).getBytes(StandardCharsets.UTF_8)).get();

        // seen counts calls as they start. Waiting for both to return (the row included)
        // means nothing of this test is still writing when the next test empties the tables.
        await().atMost(PATIENCE).until(() -> finished.get() == 2);
        assertThat(seen).containsExactly(false, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_usage", Long.class)).isOne();
    }
}
