package com.basketry.inventory.request;

import static com.basketry.inventory.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.basketry.inventory.counter.StockCounter;
import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.outbox.OutboxStatus;
import com.basketry.inventory.outbox.StockResultEvents;
import com.basketry.inventory.stock.InvalidStockRequestException;
import com.basketry.inventory.stock.ReasonCode;
import com.basketry.inventory.stock.ReservationStatus;
import com.basketry.inventory.stock.ReserveCommand;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TopicReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Stubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The real listeners, error handler and relay against a real broker (embedded,
 * so no Docker): a request in, a result out. The request topics are created
 * here, as order-service would; everything else only if the service creates
 * it. Every test uses its own orders and products, because the context is
 * shared. Redis failures are made short-lived here, so one test's outage
 * never makes another skip the counter.
 */
@EmbeddedKafka(
        topics = {StockRequestKafkaTests.RESERVE_TOPIC, StockRequestKafkaTests.RELEASE_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.stock-requests.consumer.enabled=true",
        "basketry.stock-requests.retry-initial=5ms",
        "basketry.stock-requests.retry-max=20ms",
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms",
        "basketry.outbox.relay.backoff-initial=50ms",
        "basketry.outbox.relay.backoff-max=200ms",
        "basketry.stock-counter.skip-after-failure=1ms"})
class StockRequestKafkaTests extends InventoryServiceTestBase {

    static final String RESERVE_TOPIC = "order.stock-reserve.requested";
    static final String RELEASE_TOPIC = "order.stock-release.requested";
    private static final String RESERVE_DLT = RESERVE_TOPIC + ".DLT";
    private static final String RELEASE_DLT = RELEASE_TOPIC + ".DLT";
    private static final AtomicLong NEXT_ORDER = new AtomicLong(1000);
    private static final AtomicLong NEXT_PRODUCT = new AtomicLong(500);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @MockitoSpyBean
    private StockService spiedStock;

    @MockitoSpyBean
    private StockCounter spiedCounter;

    @MockitoSpyBean
    private KafkaAdmin spiedAdmin;

    static String request(long orderId, long productId, String quantity) {
        return """
                {"schemaVersion":1,"eventId":"6f1c2c1e-1b7a-4f55-9d55-1b5e0c7c9a10","orderId":%d,"productId":%d,\
                "quantity":%s,"occurredAt":"2026-11-09T10:00:00Z"}""".formatted(orderId, productId, quantity);
    }

    private static long newOrder() {
        return NEXT_ORDER.incrementAndGet();
    }

    private static long newProduct() {
        return NEXT_PRODUCT.incrementAndGet();
    }

    private void send(String topic, long orderId, String value) throws Exception {
        kafka.send(topic, Long.toString(orderId), value).get();
    }

    private List<OutboxEvent> rowsOf(long orderId) {
        return outboxRows().stream().filter(row -> row.getMessageKey().equals(Long.toString(orderId))).toList();
    }

    private void awaitRows(long orderId, int count) {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(rowsOf(orderId)).hasSize(count)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
    }

    private List<ConsumerRecord<String, String>> results(String topic, long orderId, int expected) {
        return TopicReader.readKey(broker, topic, Long.toString(orderId), expected);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(ConsumerRecord<String, String> record) {
        return json.readValue(record.value(), Map.class);
    }

    /** Every dead-letter header about the failure: only our two may be there. */
    private static List<String> exceptionHeaders(ConsumerRecord<String, String> record) {
        return java.util.stream.StreamSupport.stream(record.headers().spliterator(), false)
                .map(Header::key).filter(key -> key.startsWith("kafka_dlt-exception")).toList();
    }

    // ---- the end-to-end path, on the embedded broker ----------------------------

    @Test
    void aReserveRequestInIsAReservedResultOutAndAReleaseGivesItBack() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);

        send(RESERVE_TOPIC, order, request(order, product, "3"));

        assertThat(payload(results(StockResultEvents.RESERVED_TOPIC, order, 1).getFirst()))
                .containsEntry("orderId", (int) order).containsEntry("productId", (int) product)
                .doesNotContainKey("quantity");
        assertThat(quantityOf(product)).isEqualTo(7);
        assertThat(counterOf(product)).hasValue(7);

        send(RELEASE_TOPIC, order, request(order, product, "3"));

        assertThat(payload(results(StockResultEvents.RELEASED_TOPIC, order, 1).getFirst()))
                .containsEntry("orderId", (int) order).containsEntry("productId", (int) product);
        assertThat(quantityOf(product)).isEqualTo(10);
        // The counter gets its share after the commit, so possibly after the result went out.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(counterOf(product)).hasValue(10));
    }

    // ---- repeats and order --------------------------------------------------------

    /** At-least-once delivery: the same request twice reserves once, takes from Redis once, answers twice. */
    @Test
    void aDuplicateReserveReservesOnceTakesFromTheCounterOnceAndAnswersTwice() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        String reserve = request(order, product, "3");

        send(RESERVE_TOPIC, order, reserve);
        send(RESERVE_TOPIC, order, reserve);

        assertThat(results(StockResultEvents.RESERVED_TOPIC, order, 2)).hasSize(2);
        assertThat(reservation(order, product).getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(quantityOf(product)).isEqualTo(7);
        assertThat(counterOf(product)).hasValue(7);
        verify(spiedCounter, times(1)).take(eq(product), anyInt());
    }

    /** A reserve replayed after its release answers "reserved" again and takes nothing. */
    @Test
    void aReserveReplayedAfterItsReleaseTakesNothing() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        String reserve = request(order, product, "3");

        send(RESERVE_TOPIC, order, reserve);
        awaitRows(order, 1);
        send(RELEASE_TOPIC, order, request(order, product, "3"));
        awaitRows(order, 2);
        send(RESERVE_TOPIC, order, reserve);
        awaitRows(order, 3);

        assertThat(rowsOf(order)).extracting(OutboxEvent::getTopic).containsExactly(
                StockResultEvents.RESERVED_TOPIC, StockResultEvents.RELEASED_TOPIC, StockResultEvents.RESERVED_TOPIC);
        assertThat(reservation(order, product).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(quantityOf(product)).isEqualTo(10);
        assertThat(counterOf(product)).hasValue(10);
        verify(spiedCounter, times(1)).take(eq(product), anyInt());
    }

    /**
     * A release first stores a tombstone and answers "released"; the reserve
     * that arrives after it fails with RELEASED_BEFORE_RESERVE and takes nothing.
     */
    @Test
    void aReleaseBeforeItsReserveLeavesATombstoneSoTheReserveTakesNothing() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);

        send(RELEASE_TOPIC, order, request(order, product, "3"));
        awaitRows(order, 1);
        send(RESERVE_TOPIC, order, request(order, product, "3"));

        assertThat(results(StockResultEvents.RELEASED_TOPIC, order, 1)).hasSize(1);
        assertThat(payload(results(StockResultEvents.RESERVATION_FAILED_TOPIC, order, 1).getFirst()))
                .containsEntry("reasonCode", ReasonCode.RELEASED_BEFORE_RESERVE.name());
        assertThat(reservation(order, product).getReasonCode()).isEqualTo(ReasonCode.RELEASED_BEFORE_RESERVE);
        assertThat(quantityOf(product)).isEqualTo(10);
        assertThat(counterOf(product)).hasValue(10);
        verify(spiedCounter, never()).take(eq(product), anyInt());
    }

    // ---- the quantity rules ---------------------------------------------------------

    /**
     * 4294967301 is 2^32 + 5. Any quantity above the limit, of any size, is
     * answered QUANTITY_OVER_LIMIT and takes nothing; the stored row holds the
     * request capped at Integer.MAX_VALUE.
     */
    @Test
    void aQuantityOfAnySizeAboveTheLimitIsAnsweredQuantityOverLimit() throws Exception {
        long product = newProduct();
        givenStockAndCounter(product, 10);
        long wraps = newOrder();
        long huge = newOrder();
        long justOver = newOrder();

        send(RESERVE_TOPIC, wraps, request(wraps, product, "4294967301"));
        send(RESERVE_TOPIC, huge, request(huge, product, "1000000000000000000000000000000"));
        send(RESERVE_TOPIC, justOver, request(justOver, product, "1001"));

        for (long order : List.of(wraps, huge, justOver)) {
            assertThat(payload(results(StockResultEvents.RESERVATION_FAILED_TOPIC, order, 1).getFirst()))
                    .containsEntry("reasonCode", ReasonCode.QUANTITY_OVER_LIMIT.name());
        }
        assertThat(reservation(wraps, product).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(reservation(huge, product).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(reservation(justOver, product).getQuantity()).isEqualTo(1001);
        assertThat(quantityOf(product)).isEqualTo(10);
        verify(spiedCounter, never()).take(eq(product), anyInt());
    }

    /** A release's quantity is only compared: a huge one is answered, and the stored quantity goes back. */
    @Test
    void aReleaseWithAHugeQuantityGivesBackTheStoredQuantity() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        send(RESERVE_TOPIC, order, request(order, product, "3"));
        awaitRows(order, 1);

        send(RELEASE_TOPIC, order, request(order, product, "1000000000000000000000000000000"));

        assertThat(results(StockResultEvents.RELEASED_TOPIC, order, 1)).hasSize(1);
        assertThat(quantityOf(product)).isEqualTo(10);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(counterOf(product)).hasValue(10));
    }

    // ---- the dead-letter topics -----------------------------------------------------

    /**
     * Every record that can never succeed goes to its own topic's dead-letter
     * topic with its exact bytes and only our two exception headers, changes
     * nothing, publishes nothing back, and the record behind it is handled.
     */
    @Test
    void badRequestsAreDeadLetteredWithTheirExactBytesAndOnlyOurTwoHeaders() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        List<String> badReserves = List.of(
                "not json",
                request(order, product, "3").replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                request(order, product, "3").replace("\"quantity\"", "\"warehouse\":1,\"quantity\""),
                request(order, product, "3").replace("\"orderId\":" + order, "\"orderId\":0"),
                request(order, product, "0"),
                request(order, product, "-1"),
                request(order, product, "1.5"),
                request(order, product, "1e3"),
                request(order, product, "\"3\""));
        List<String> badReleases = List.of(
                request(order, product, "0"),
                request(order, product, "3").replace("\"quantity\"", "\"merchantId\":3,\"quantity\""));
        for (String bad : badReserves) {
            send(RESERVE_TOPIC, order, bad);
        }
        send(RESERVE_TOPIC, order, null);
        for (String bad : badReleases) {
            send(RELEASE_TOPIC, order, bad);
        }
        send(RESERVE_TOPIC, order, request(order, product, "3"));

        List<ConsumerRecord<String, String>> reserveDeadLetters = TopicReader.readKey(broker, RESERVE_DLT,
                Long.toString(order), badReserves.size() + 1);
        List<ConsumerRecord<String, String>> releaseDeadLetters = TopicReader.readKey(broker, RELEASE_DLT,
                Long.toString(order), badReleases.size());
        assertThat(reserveDeadLetters).extracting(ConsumerRecord::value)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(badReserves.stream(),
                        java.util.stream.Stream.of((String) null)).toList());
        assertThat(releaseDeadLetters).extracting(ConsumerRecord::value).containsExactlyElementsOf(badReleases);
        for (ConsumerRecord<String, String> deadLetter : java.util.stream.Stream.concat(
                reserveDeadLetters.stream(), releaseDeadLetters.stream()).toList()) {
            assertThat(exceptionHeaders(deadLetter)).containsExactlyInAnyOrder(
                    KafkaHeaders.DLT_EXCEPTION_FQCN, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(InvalidStockRequestException.class.getName());
            assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                    .isEqualTo(deadLetter.topic().replace(".DLT", ""));
        }
        assertThat(reserveDeadLetters).extracting(record -> TopicReader.header(record,
                KafkaHeaders.DLT_EXCEPTION_MESSAGE)).containsExactly(
                "The value is not valid JSON (line 1, column 1)",
                "schemaVersion is missing or not 1",
                "The value has a field that schemaVersion 1 does not have",
                "orderId must be positive",
                "quantity must be positive",
                "quantity must be positive",
                "quantity must be a whole JSON number",
                "quantity must be a whole JSON number",
                "quantity must be a whole JSON number",
                "The record has no value");
        // The good record behind them all is handled; nothing else was published.
        awaitRows(order, 1);
        assertThat(rowsOf(order)).extracting(OutboxEvent::getTopic).containsExactly(StockResultEvents.RESERVED_TOPIC);
        assertThat(quantityOf(product)).isEqualTo(7);
    }

    /** A bug is not a database failure: it must not block the partition, and its message is not ours to quote. */
    @Test
    void anUnexpectedFailureIsDeadLetteredWithoutItsMessage() throws Exception {
        long order = newOrder();
        long next = order + 1_000_000;
        long product = newProduct();
        givenStockAndCounter(product, 10);
        doThrow(new IllegalStateException("a bug that quotes the request"))
                .when(spiedStock).release(argThat(command -> command.orderId() == order));

        send(RELEASE_TOPIC, order, request(order, product, "3"));
        send(RELEASE_TOPIC, order, request(next, product, "3"));

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, RELEASE_DLT,
                Long.toString(order), 1).getFirst();
        assertThat(exceptionHeaders(deadLetter)).containsExactlyInAnyOrder(
                KafkaHeaders.DLT_EXCEPTION_FQCN, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo(StockRequestConsumerConfig.UNEXPECTED);
        awaitRows(next, 1);
    }

    /** The dead-letter publish failing (here: the topics cannot be checked) keeps the record. */
    @Test
    void aFailedDeadLetterPublishIsTriedAgainNotDropped() throws Exception {
        long order = newOrder();
        // Only the listener's check fails, once: the relay checks the topics too.
        AtomicInteger refusals = new AtomicInteger();
        doAnswer(call -> Thread.currentThread().getName().startsWith("reserve-requests")
                && refusals.getAndIncrement() == 0 ? false : call.callRealMethod())
                .when(spiedAdmin).initialize();

        send(RESERVE_TOPIC, order, "not json");

        assertThat(TopicReader.readKey(broker, RESERVE_DLT, Long.toString(order), 1))
                .extracting(ConsumerRecord::value).containsExactly("not json");
        assertThat(refusals).hasValueGreaterThan(1);
    }

    // ---- outages: retried without limit, or never an error --------------------------

    /** 16 failures: more than the error handler's default limit of 9 retries, and than 15. */
    @Test
    void aDatabaseFailureIsRetriedWithoutLimitAndNothingIsLost() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        Stubber failures = doThrow(new DataAccessResourceFailureException("database down"));
        for (int failure = 2; failure <= 16; failure++) {
            failures = failures.doThrow(new CannotCreateTransactionException("database down"));
        }
        failures.doCallRealMethod().when(spiedStock).reserve(argThat(command -> command.orderId() == order));

        send(RESERVE_TOPIC, order, request(order, product, "3"));

        awaitRows(order, 1);
        verify(spiedStock, atLeast(17)).reserve(argThat((ReserveCommand command) -> command.orderId() == order));
        assertThat(reservation(order, product).getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(quantityOf(product)).isEqualTo(7);
        assertNoDeadLetter(RESERVE_DLT, order);
    }

    /**
     * Redis failing on every call for this product: the request is still
     * decided, by PostgreSQL alone, and nothing is retried or dead-lettered.
     * The counter is left as it was, for reconciliation to repair.
     */
    @Test
    void redisDownWhileConsumingFallsBackToPostgres() throws Exception {
        long order = newOrder();
        long product = newProduct();
        givenStockAndCounter(product, 10);
        RedisConnectionFailureException down = new RedisConnectionFailureException("Redis is down");
        doThrow(down).when(spiedCounter).take(eq(product), anyInt());
        doThrow(down).when(spiedCounter).addIfLoaded(eq(product), anyLong());
        doThrow(down).when(spiedCounter).loadIfAbsent(eq(product), anyLong());

        send(RESERVE_TOPIC, order, request(order, product, "3"));
        awaitRows(order, 1);
        send(RELEASE_TOPIC, order, request(order, product, "3"));
        awaitRows(order, 2);

        assertThat(rowsOf(order)).extracting(OutboxEvent::getTopic)
                .containsExactly(StockResultEvents.RESERVED_TOPIC, StockResultEvents.RELEASED_TOPIC);
        assertThat(quantityOf(product)).isEqualTo(10);
        assertThat(counterOf(product)).hasValue(10);
        verify(spiedStock, times(1)).reserve(argThat((ReserveCommand command) -> command.orderId() == order));
        assertNoDeadLetter(RESERVE_DLT, order);
    }

    // ---- settings -----------------------------------------------------------------

    /** One consumer group for the service: a listener id must not become a group of its own. */
    @Test
    void bothListenersAreInTheServicesConsumerGroup() {
        assertThat(List.of("reserve-requests", "release-requests")).allSatisfy(id ->
                assertThat(registry.getListenerContainer(id).getGroupId()).isEqualTo("inventory-service"));
    }

    /** About 2 s a record with a slow Redis: 50 a poll stays inside the 5-minute poll interval. */
    @Test
    void aPollTakesAtMostFiftyRecords() {
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    }

    /** The request topics are order-service's: nothing here may create them. */
    @Test
    void theServiceDeclaresOnlyItsOwnTopics() {
        assertThat(context.getBeansOfType(NewTopic.class).values()).extracting(NewTopic::name)
                .containsExactlyInAnyOrder(StockResultEvents.RESERVED_TOPIC,
                        StockResultEvents.RESERVATION_FAILED_TOPIC, StockResultEvents.RELEASED_TOPIC,
                        RESERVE_DLT, RELEASE_DLT);
    }

    /** A marker behind everything else for this key shows that the topic was read to the end. */
    private void assertNoDeadLetter(String topic, long order) throws Exception {
        kafka.send(topic, Long.toString(order), "marker").get();
        assertThat(TopicReader.readKey(broker, topic, Long.toString(order), 1))
                .extracting(ConsumerRecord::value).containsExactly("marker");
    }
}
