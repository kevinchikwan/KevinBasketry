package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.outbox.OutboxStatus;
import com.basketry.payment.outbox.PaymentResultEvents;
import com.basketry.payment.payment.ChargeCommand;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.InvalidPaymentRequestException;
import com.basketry.payment.payment.NotRefundableException;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.payment.PaymentTransaction;
import com.basketry.payment.payment.RefundMismatchException;
import com.basketry.payment.payment.UnknownTransactionException;
import com.basketry.payment.support.PaymentServiceTestBase;
import com.basketry.payment.support.TopicReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Stubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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
 * it. Every test uses its own orders, because the context is shared.
 */
@EmbeddedKafka(
        topics = {PaymentRequestKafkaTests.CHARGE_TOPIC, PaymentRequestKafkaTests.REFUND_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "basketry.payment-requests.retry-initial=5ms",
        "basketry.payment-requests.retry-max=20ms",
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms",
        "basketry.outbox.relay.backoff-initial=50ms",
        "basketry.outbox.relay.backoff-max=200ms"})
class PaymentRequestKafkaTests extends PaymentServiceTestBase {

    static final String CHARGE_TOPIC = "order.merchant-charge.requested";
    static final String REFUND_TOPIC = "order.merchant-refund.requested";
    private static final String CHARGE_DLT = CHARGE_TOPIC + ".DLT";
    private static final String REFUND_DLT = REFUND_TOPIC + ".DLT";
    private static final AtomicLong NEXT_ORDER = new AtomicLong(1000);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoSpyBean
    private PaymentService spiedPayments;

    @MockitoSpyBean
    private KafkaAdmin spiedAdmin;

    static String chargeRequest(long orderId, long merchantId, String amount, String cardNumber) {
        return """
                {"schemaVersion":1,"eventId":"6f1c2c1e-1b7a-4f55-9d55-1b5e0c7c9a10","orderId":%d,"merchantId":%d,\
                "amount":%s,"cardNumber":"%s","occurredAt":"2026-11-08T10:00:00Z"}"""
                .formatted(orderId, merchantId, amount, cardNumber);
    }

    static String refundRequest(long orderId, long merchantId, String transactionRef) {
        return """
                {"schemaVersion":1,"eventId":"0b9d3f6e-2f5a-4b8e-8f3d-6a1c2e9b7d40","orderId":%d,"merchantId":%d,\
                "transactionRef":"%s","occurredAt":"2026-11-08T10:05:00Z"}"""
                .formatted(orderId, merchantId, transactionRef);
    }

    private static long newOrder() {
        return NEXT_ORDER.incrementAndGet();
    }

    private void send(String topic, long orderId, String value) throws Exception {
        kafka.send(topic, Long.toString(orderId), value).get();
    }

    private List<PaymentTransaction> transactionsOf(long orderId) {
        return transactions.findAll().stream().filter(row -> row.getOrderId() == orderId).toList();
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

    private Map<String, Object> payload(ConsumerRecord<String, String> record) {
        return json.readValue(record.value(), Map.class);
    }

    @Test
    void aChargeRequestInIsAChargedResultOut() throws Exception {
        long order = newOrder();

        send(CHARGE_TOPIC, order, chargeRequest(order, 3, "25.50", APPROVED_CARD));

        Map<String, Object> charged = payload(results(PaymentResultEvents.CHARGED_TOPIC, order, 1).getFirst());
        assertThat(charged).containsEntry("orderId", (int) order).containsEntry("merchantId", 3)
                .containsEntry("transactionRef", transactionsOf(order).getFirst().getTransactionRef());
    }

    @Test
    void aDeclinedCardIsADeclinedResult() throws Exception {
        long order = newOrder();

        send(CHARGE_TOPIC, order, chargeRequest(order, 3, "25.50", INSUFFICIENT_FUNDS_CARD));

        assertThat(payload(results(PaymentResultEvents.DECLINED_TOPIC, order, 1).getFirst()))
                .containsEntry("reasonCode", "51");
    }

    /** At-least-once delivery: the same request twice charges once and answers twice, alike. */
    @Test
    void aDuplicateRequestChargesOnceAndPublishesChargedTwiceWithTheSameRef() throws Exception {
        long order = newOrder();
        String request = chargeRequest(order, 3, "25.50", APPROVED_CARD);

        send(CHARGE_TOPIC, order, request);
        send(CHARGE_TOPIC, order, request);

        List<ConsumerRecord<String, String>> charged = results(PaymentResultEvents.CHARGED_TOPIC, order, 2);
        String ref = transactionsOf(order).getFirst().getTransactionRef();
        assertThat(transactionsOf(order)).hasSize(1);
        assertThat(charged).extracting(record -> payload(record).get("transactionRef")).containsExactly(ref, ref);
    }

    @Test
    void aRefundRequestInIsARefundedResultOut() throws Exception {
        long order = newOrder();
        Charged charged = (Charged) payments.authorize(charge(order, 3, "25.50", APPROVED_CARD));

        send(REFUND_TOPIC, order, refundRequest(order, 3, charged.transactionRef()));

        assertThat(payload(results(PaymentResultEvents.REFUNDED_TOPIC, order, 1).getFirst()))
                .containsEntry("orderId", (int) order).containsEntry("merchantId", 3);
    }

    /**
     * A record that can never succeed goes to its own topic's dead-letter
     * topic with its exact bytes and only our two exception headers, changes
     * nothing, publishes nothing back, and the record behind it is handled.
     */
    @Test
    void aBadChargeRequestIsDeadLetteredAndTheNextIsHandled() throws Exception {
        long order = newOrder();
        String bad = chargeRequest(order, 3, "\"25.50\"", APPROVED_CARD);

        send(CHARGE_TOPIC, order, bad);
        send(CHARGE_TOPIC, order, chargeRequest(order, 4, "1.00", APPROVED_CARD));

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, CHARGE_DLT,
                Long.toString(order), 1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(bad);
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(CHARGE_TOPIC);
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(InvalidPaymentRequestException.class.getName());
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo("amount must be a JSON number");
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isNull();
        awaitRows(order, 1);
        assertThat(transactionsOf(order)).singleElement()
                .satisfies(row -> assertThat(row.getMerchantId()).isEqualTo(4));
    }

    @Test
    void aRecordWithoutAValueIsDeadLettered() throws Exception {
        long order = newOrder();

        send(CHARGE_TOPIC, order, null);

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, CHARGE_DLT,
                Long.toString(order), 1).getFirst();
        assertThat(deadLetter.value()).isNull();
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo("The record has no value");
    }

    /** The three refunds Section 2 rejects, each to the refund dead-letter topic, nothing changed. */
    @Test
    void rejectedRefundsAreDeadLetteredAndChangeNothing() throws Exception {
        long order = newOrder();
        Charged charged = (Charged) payments.authorize(charge(order, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(order, 4, "25.50", DO_NOT_HONOR_CARD));
        String declinedRef = transactionsOf(order).stream().filter(row -> row.getMerchantId() == 4)
                .findFirst().orElseThrow().getTransactionRef();
        awaitRows(order, 2);

        send(REFUND_TOPIC, order, refundRequest(order, 3, "TXN-00000000000000000000000000000000"));
        send(REFUND_TOPIC, order, refundRequest(order, 4, declinedRef));
        send(REFUND_TOPIC, order, refundRequest(order, 9, charged.transactionRef()));

        List<ConsumerRecord<String, String>> deadLetters = TopicReader.readKey(broker, REFUND_DLT,
                Long.toString(order), 3);
        assertThat(deadLetters).extracting(record -> TopicReader.header(record, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .containsExactly(UnknownTransactionException.class.getName(), NotRefundableException.class.getName(),
                        RefundMismatchException.class.getName());
        assertThat(rowsOf(order)).hasSize(2);
        assertThat(transactionsOf(order)).extracting(PaymentTransaction::getRefundedAt).containsOnlyNulls();
    }

    /** More failures than the error handler's default limit of 9 retries. */
    @Test
    void aDatabaseFailureIsRetriedWithoutLimitAndNothingIsLost() throws Exception {
        long order = newOrder();
        Stubber failures = doThrow(new DataAccessResourceFailureException("database down"));
        for (int failure = 1; failure < 15; failure++) {
            failures = failures.doThrow(new CannotCreateTransactionException("database down"));
        }
        failures.doCallRealMethod().when(spiedPayments).authorize(argThat(command -> command.orderId() == order));

        send(CHARGE_TOPIC, order, chargeRequest(order, 3, "25.50", APPROVED_CARD));

        awaitRows(order, 1);
        verify(spiedPayments, atLeast(16)).authorize(argThat((ChargeCommand command) -> command.orderId() == order));
        assertThat(transactionsOf(order)).hasSize(1);
        assertNoDeadLetter(CHARGE_DLT, order);
    }

    /** A bug is not a database failure: it must not block the partition. */
    @Test
    void anUnexpectedFailureIsDeadLetteredWithoutItsMessage() throws Exception {
        long order = newOrder();
        doThrow(new IllegalStateException("a bug that quotes 4000000000000002"))
                .when(spiedPayments).authorize(argThat(command -> command.orderId() == order));

        send(CHARGE_TOPIC, order, chargeRequest(order, 3, "25.50", APPROVED_CARD));
        send(CHARGE_TOPIC, order, chargeRequest(order + 1_000_000, 3, "1.00", APPROVED_CARD));

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, CHARGE_DLT,
                Long.toString(order), 1).getFirst();
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo(PaymentRequestConsumerConfig.UNEXPECTED);
        await().atMost(PATIENCE).until(() -> transactionsOf(order + 1_000_000).size() == 1);
    }

    /** The dead-letter publish failing (here: the topics cannot be checked) keeps the record. */
    @Test
    void aFailedDeadLetterPublishIsTriedAgainNotDropped() throws Exception {
        long order = newOrder();
        // Only the listener's check fails, once: the relay checks the topics too.
        AtomicInteger refusals = new AtomicInteger();
        doAnswer(call -> Thread.currentThread().getName().startsWith("charge-requests")
                && refusals.getAndIncrement() == 0 ? false : call.callRealMethod())
                .when(spiedAdmin).initialize();

        send(CHARGE_TOPIC, order, "not json");

        assertThat(TopicReader.readKey(broker, CHARGE_DLT, Long.toString(order), 1))
                .extracting(ConsumerRecord::value).containsExactly("not json");
        assertThat(refusals).hasValueGreaterThan(1);
    }

    /** One consumer group for the service: a listener id must not become a group of its own. */
    @Test
    void bothListenersAreInTheServicesConsumerGroup() {
        assertThat(List.of("charge-requests", "refund-requests")).allSatisfy(id ->
                assertThat(registry.getListenerContainer(id).getGroupId()).isEqualTo("payment-service"));
    }

    /** The request topics are order-service's: nothing here may create them. */
    @Test
    void theServiceDeclaresOnlyItsOwnTopics() {
        assertThat(context.getBeansOfType(NewTopic.class).values()).extracting(NewTopic::name)
                .containsExactlyInAnyOrder(PaymentResultEvents.CHARGED_TOPIC, PaymentResultEvents.DECLINED_TOPIC,
                        PaymentResultEvents.REFUNDED_TOPIC, CHARGE_DLT, REFUND_DLT);
    }

    /** A marker behind everything else for this key shows that the topic was read to the end. */
    private void assertNoDeadLetter(String topic, long order) throws Exception {
        kafka.send(topic, Long.toString(order), "marker").get();
        assertThat(TopicReader.readKey(broker, topic, Long.toString(order), 1))
                .extracting(ConsumerRecord::value).containsExactly("marker");
    }

}
