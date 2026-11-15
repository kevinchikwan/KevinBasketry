package com.basketry.order.results;

import static com.basketry.order.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.saga.SagaResult;
import com.basketry.order.saga.SagaService;
import com.basketry.order.support.SagaTestBase;
import com.basketry.order.support.TopicReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The six result listeners against a real broker (embedded, so no Docker),
 * which, like the Compose broker, creates no topic by itself: the result topics
 * are created here as payment-service and inventory-service would, the
 * dead-letter topics by order-service (DESIGN.md Section 5, order-service
 * results).
 */
@EmbeddedKafka(
        topics = {ResultTopics.CHARGED, ResultTopics.DECLINED, ResultTopics.REFUNDED, ResultTopics.RESERVED,
                ResultTopics.RESERVATION_FAILED, ResultTopics.RELEASED},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.order-results.consumer.enabled=true",
        "basketry.order-results.retry-initial=5ms",
        "basketry.order-results.retry-max=20ms"})
class ResultListenerKafkaTests extends SagaTestBase {

    /** Written out, not built by ResultTopics, so a wrong suffix there is caught. */
    private static final String DLT = "order.merchant.charged.order-service.DLT";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoSpyBean
    private SagaService spiedSaga;

    /**
     * The parser has no proxy and runs on the listener's thread just before the
     * hand-over, so it sees the listener's own state; SagaService's spy sits
     * inside its transactional proxy and would always see its own transaction.
     */
    @MockitoSpyBean
    private ResultParser spiedParser;

    /** For every result the listeners parsed: whether a transaction was open at that moment. */
    private final List<Boolean> transactionOpenInTheListener = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordTheListenersState() {
        transactionOpenInTheListener.clear();
        doAnswer(invocation -> {
            transactionOpenInTheListener.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(spiedParser).parse(any(), any());
    }

    private void send(String topic, long orderId, String value) throws Exception {
        kafka.send(topic, Long.toString(orderId), value).get();
    }

    private static String chargedJson(long orderId, long merchantId) {
        return "{\"schemaVersion\":1,\"orderId\":" + orderId + ",\"merchantId\":" + merchantId
                + ",\"transactionRef\":\"" + ref(merchantId) + "\"}";
    }

    private static String lineJson(long orderId, long productId) {
        return "{\"schemaVersion\":1,\"orderId\":" + orderId + ",\"productId\":" + productId + "}";
    }

    /**
     * Results on the topics drive the order to SUCCEEDED, and no listener hands
     * a result over inside a transaction. Mutations: the reserved listener
     * subscribed to order.stock.released (reservations never read);
     * {@code @Transactional} on the listener's handle method.
     */
    @Test
    void resultsOnTheTopicsDriveTheOrder() throws Exception {
        long id = order();

        send(ResultTopics.CHARGED, id, chargedJson(id, MERCHANT));
        send(ResultTopics.CHARGED, id, chargedJson(id, OTHER_MERCHANT));
        send(ResultTopics.RESERVED, id, lineJson(id, PRODUCT_A));
        send(ResultTopics.RESERVED, id, lineJson(id, PRODUCT_B));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.SUCCEEDED));
        assertThat(transactionOpenInTheListener).hasSize(4).containsOnly(false);
    }

    /**
     * A malformed result goes to order-service's own dead-letter topic, byte
     * for byte, keyed like the original, with only our two exception headers;
     * the next record of the partition is still applied. Mutations: Spring's
     * default exception headers (a stack trace header appears); the
     * dead-letter topic without ".order-service".
     */
    @Test
    void aMalformedResultIsDeadLetteredAndTheNextOneApplied() throws Exception {
        long id = order();
        String malformed = "{\"schemaVersion\":1,\"orderId\":" + id + ",\"merchantId\":3,\"transactionRef\":\"nope\"}";

        send(ResultTopics.CHARGED, id, malformed);
        send(ResultTopics.CHARGED, id, chargedJson(id, MERCHANT));

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, DLT, Long.toString(id), 1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(malformed);
        List<String> headers = new ArrayList<>();
        for (Header header : deadLetter.headers()) {
            headers.add(header.key() + "=" + new String(header.value(), StandardCharsets.UTF_8));
        }
        assertThat(headers).contains(
                "kafka_dlt-exception-fqcn=" + InvalidResultException.class.getName(),
                "kafka_dlt-exception-message=transactionRef must be TXN- and 32 lowercase hex digits");
        assertThat(headers).noneMatch(header -> header.startsWith("kafka_dlt-exception-stacktrace")
                || header.startsWith("kafka_dlt-exception-cause-fqcn"));
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(chargeOf(id, MERCHANT).getStatus()).isEqualTo(ChargeStatus.CHARGED));
    }

    /**
     * The six listeners form one consumer group, order-service (the
     * spring.kafka.consumer.group-id), as payment-service's and
     * inventory-service's do, and between them hold every partition of the six
     * result topics. TopicReader's own groups ("test-...") are left out.
     * Mutation: one listener without idIsGroup = false (it forms its own group,
     * named after its id).
     */
    @Test
    void theSixListenersFormOneConsumerGroup() {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            await().atMost(PATIENCE).untilAsserted(() -> {
                List<String> groups = admin.listGroups().all().get().stream()
                        .map(GroupListing::groupId)
                        .filter(group -> !group.startsWith("test-"))
                        .sorted()
                        .toList();
                assertThat(groups).containsExactly("order-service");
                ConsumerGroupDescription group = admin.describeConsumerGroups(List.of("order-service"))
                        .all().get().get("order-service");
                assertThat(group.members()).hasSize(6);
                Set<String> assigned = new TreeSet<>();
                long partitions = 0;
                for (MemberDescription member : group.members()) {
                    for (TopicPartition partition : member.assignment().topicPartitions()) {
                        assigned.add(partition.topic());
                        partitions++;
                    }
                }
                assertThat(assigned).containsExactlyInAnyOrder(ResultTopics.CHARGED, ResultTopics.DECLINED,
                        ResultTopics.REFUNDED, ResultTopics.RESERVED, ResultTopics.RESERVATION_FAILED,
                        ResultTopics.RELEASED);
                assertThat(partitions).isEqualTo(18);
            });
        }
    }

    /**
     * A database failure is retried, never dead-lettered: the result is applied
     * once the database answers. Mutation: isDatabaseFailure always false (the
     * result goes to the dead-letter topic and is lost to the order).
     */
    @Test
    void aDatabaseFailureIsRetriedNotDeadLettered() throws Exception {
        long id = order();
        AtomicInteger failures = new AtomicInteger();
        doAnswer(invocation -> {
            if (failures.incrementAndGet() <= 2) {
                throw new DataAccessResourceFailureException("database down");
            }
            return invocation.callRealMethod();
        }).when(spiedSaga).apply(argThat(result -> result instanceof SagaResult.Reserved));

        send(ResultTopics.RESERVED, id, lineJson(id, PRODUCT_A));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(lineOf(id, PRODUCT_A).getStatus()).isEqualTo(com.basketry.order.order.LineStatus.RESERVED));
        assertThat(failures.get()).isEqualTo(3);
    }
}
