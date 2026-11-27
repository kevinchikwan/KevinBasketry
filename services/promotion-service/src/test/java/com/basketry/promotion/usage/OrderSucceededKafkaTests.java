package com.basketry.promotion.usage;

import static com.basketry.promotion.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.support.TopicReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Stubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.node.ObjectNode;

/**
 * The order.succeeded listener against a real broker (embedded, so no Docker),
 * which, like the Compose broker, creates no topic by itself: order.succeeded
 * is created here as order-service would, the dead-letter topic by
 * promotion-service (DESIGN.md Section 5, promotion-service).
 */
@EmbeddedKafka(
        topics = OrderSucceededTopic.NAME,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.coupon-usage.consumer.enabled=true",
        "basketry.coupon-usage.retry-initial=5ms",
        "basketry.coupon-usage.retry-max=20ms"})
class OrderSucceededKafkaTests extends PromotionServiceTestBase {

    /** Written out, not built by OrderSucceededTopic, so a wrong suffix there is caught. */
    static final String DLT = "order.succeeded.promotion-service.DLT";

    @Autowired
    private KafkaTemplate<byte[], byte[]> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private CouponRepository coupons;

    @MockitoSpyBean
    private CouponUsageService spiedUsages;

    private long couponId;

    @BeforeEach
    void aCoupon() {
        couponId = coupons.save(new Coupon(new CouponCode("SPRING10"), CouponScope.MERCHANT, 3L,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), CouponUsageServiceTests.FAR_FUTURE))
                .getId();
    }

    static byte[] key(long orderId) {
        return Long.toString(orderId).getBytes(StandardCharsets.UTF_8);
    }

    /** The golden event as an order that carried this code, with its own eventId and orderId. */
    static String event(long orderId, String code) {
        return OrderSucceededParserTests.goldenWith(event -> {
            event.put("eventId", UUID.randomUUID().toString());
            event.put("orderId", orderId);
            event.put("couponCode", code);
            ((ObjectNode) event.get("merchants").get(0)).put("discount", new BigDecimal("2.55"));
        });
    }

    private void send(long orderId, byte[] value) throws Exception {
        kafka.send(OrderSucceededTopic.NAME, key(orderId), value).get();
    }

    private void send(long orderId, String value) throws Exception {
        send(orderId, value.getBytes(StandardCharsets.UTF_8));
    }

    private long countedFor(long orderId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM coupon_usage WHERE order_id = ?", Long.class, orderId);
        return count == null ? 0 : count;
    }

    private void awaitCounted(long orderId) {
        await().atMost(PATIENCE).until(() -> countedFor(orderId) == 1);
    }

    /** The one dead letter of this key: its value byte for byte, and only our two exception headers. */
    private void assertDeadLettered(long orderId, byte[] value, String exceptionClass, String message) {
        ConsumerRecord<byte[], byte[]> deadLetter = TopicReader.readKey(broker, DLT, key(orderId), 1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(value);
        List<String> headers = new ArrayList<>();
        for (Header header : deadLetter.headers()) {
            headers.add(header.key() + "=" + new String(header.value(), StandardCharsets.UTF_8));
        }
        assertThat(headers).contains("kafka_dlt-exception-fqcn=" + exceptionClass,
                "kafka_dlt-exception-message=" + message);
        assertThat(headers).noneMatch(header -> header.startsWith("kafka_dlt-exception-stacktrace")
                || header.startsWith("kafka_dlt-exception-cause-fqcn"));
    }

    private void assertNoDeadLetter(long orderId) {
        assertThat(TopicReader.readKeyFor(broker, DLT, key(orderId), Duration.ofSeconds(3))).isEmpty();
    }

    /**
     * Mutations: the listener subscribed to order.failed (nothing counted);
     * the listener not calling the service.
     */
    @Test
    void anOrderWithACouponOnTheTopicIsCounted() throws Exception {
        send(101, event(101, "SPRING10"));

        awaitCounted(101);
        assertThat(jdbc.queryForObject("SELECT coupon_id FROM coupon_usage WHERE order_id = 101", Long.class))
                .isEqualTo(couponId);
    }

    /**
     * A malformed event goes to promotion-service's own dead-letter topic, byte
     * for byte, keyed like the original, with only our two exception headers;
     * the next record of the partition is still counted. Mutations: Spring's
     * default exception headers (a stack trace header appears); the
     * dead-letter topic without ".promotion-service"; the default error handler
     * (nine retries, then logged and dropped: no dead letter).
     */
    @Test
    void aMalformedEventIsDeadLetteredAndTheNextOneCounted() throws Exception {
        String malformed = event(102, "SPRING10").replace("\"orderId\":102", "\"orderId\":\"102\"");

        send(102, malformed);
        send(102, event(102, "SPRING10"));

        assertDeadLettered(102, malformed.getBytes(StandardCharsets.UTF_8),
                InvalidOrderSucceededException.class.getName(),
                "orderId must be a positive whole JSON number that fits 64 bits");
        awaitCounted(102);
    }

    /**
     * A tombstone (no value) neither crashes nor blocks the listener. Mutation:
     * a null value read as no bytes (the dead letter says "not a JSON object").
     */
    @Test
    void aRecordWithNoValueIsDeadLetteredAndTheNextOneCounted() throws Exception {
        send(103, (byte[]) null);
        send(103, event(103, "SPRING10"));

        assertDeadLettered(103, null, InvalidOrderSucceededException.class.getName(), "The record has no value");
        awaitCounted(103);
    }

    /**
     * A value that is not UTF-8 is dead-lettered byte for byte, never counted
     * from replacement characters. Mutations: StringDeserializer for the value
     * (the dead letter's bytes differ, and the event is counted); the value
     * decoded leniently.
     */
    @Test
    void aValueThatIsNotUtf8IsDeadLetteredByteForByteAndTheNextOneCounted() throws Exception {
        byte[] good = event(104, "SPRING10").getBytes(StandardCharsets.UTF_8);
        String text = new String(good, StandardCharsets.UTF_8);
        int code = text.indexOf("SPRING10");
        byte[] bad = good.clone();
        bad[code + 2] = (byte) 0xFF;   // "SP\xFFING10": the rest is the counted event

        send(104, bad);
        send(104, good);

        assertDeadLettered(104, bad, InvalidOrderSucceededException.class.getName(), "The value is not valid UTF-8");
        awaitCounted(104);
        assertThat(countedFor(104)).isOne();
    }

    /** Mutation: any schemaVersion accepted. */
    @Test
    void anUnknownSchemaVersionIsDeadLettered() throws Exception {
        String version2 = event(105, "SPRING10").replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        send(105, version2);

        assertDeadLettered(105, version2.getBytes(StandardCharsets.UTF_8),
                InvalidOrderSucceededException.class.getName(), "schemaVersion is missing or not 1");
        assertThat(countedFor(105)).isZero();
    }

    /**
     * A code with no coupon (promotion-service lost its data) is skipped and
     * its offset committed: no dead letter, and the next record is counted.
     * Mutation: the service throws for it (it is dead-lettered).
     */
    @Test
    void aCodeWithNoCouponIsSkippedNotDeadLettered() throws Exception {
        send(106, event(106, "LOSTCODE7"));
        send(106, event(106, "SPRING10"));

        awaitCounted(106);
        assertNoDeadLetter(106);
    }

    /**
     * An unexpected error goes to the dead-letter topic with our own message,
     * never the exception's (it could quote a value). Mutation: the exception's
     * own message in the header.
     */
    @Test
    void anUnexpectedErrorIsDeadLetteredWithOurMessage() throws Exception {
        doThrow(new IllegalStateException(OrderSucceededParserTests.SENTINEL))
                .when(spiedUsages).record(argThat(event -> event.orderId() == 107));
        String value = event(107, "SPRING10");

        send(107, value);

        assertDeadLettered(107, value.getBytes(StandardCharsets.UTF_8), IllegalStateException.class.getName(),
                "Unexpected error; see the promotion-service log for this record");
    }

    /**
     * 16 failures: more than the error handler's default limit of 9 retries.
     * The first arrives wrapped, as a library may wrap it. Mutations:
     * isDatabaseFailure looks only at the top-level exception (the wrapped one
     * would go to the dead-letter topic); isDatabaseFailure without
     * TransactionException (CannotCreateTransactionException is not a
     * DataAccessException).
     */
    @Test
    void aDatabaseFailureIsRetriedWithoutLimitAndNothingIsLost() throws Exception {
        Stubber failures = doThrow(new IllegalStateException("wrapped",
                new DataAccessResourceFailureException("database down")));
        for (int failure = 2; failure <= 16; failure++) {
            failures = failures.doThrow(new CannotCreateTransactionException("database down"));
        }
        failures.doCallRealMethod().when(spiedUsages).record(argThat(event -> event.orderId() == 108));

        send(108, event(108, "SPRING10"));

        awaitCounted(108);
        verify(spiedUsages, atLeast(17)).record(argThat(event -> event.orderId() == 108));
        assertNoDeadLetter(108);
    }

    /**
     * The listener's consumer group is promotion-service (the
     * spring.kafka.consumer.group-id), and it holds every partition of
     * order.succeeded. TopicReader's own groups ("test-...") are left out.
     * Mutation: the listener without idIsGroup = false (it forms its own group,
     * named after its id).
     */
    @Test
    void theListenerIsTheOneMemberOfGroupPromotionService() {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            await().atMost(PATIENCE).untilAsserted(() -> {
                List<String> groups = admin.listGroups().all().get().stream()
                        .map(GroupListing::groupId)
                        .filter(group -> !group.startsWith("test-"))
                        .sorted()
                        .toList();
                assertThat(groups).containsExactly("promotion-service");
                ConsumerGroupDescription group = admin.describeConsumerGroups(List.of("promotion-service"))
                        .all().get().get("promotion-service");
                assertThat(group.members()).hasSize(1);
                List<TopicPartition> assigned = new ArrayList<>();
                for (MemberDescription member : group.members()) {
                    assigned.addAll(member.assignment().topicPartitions());
                }
                assertThat(assigned).extracting(TopicPartition::topic).containsOnly(OrderSucceededTopic.NAME);
                assertThat(assigned).hasSize(3);
            });
        }
    }
}
