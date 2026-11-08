package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.basketry.payment.card.CardAuthorizer;
import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.payment.ChargeCommand;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.payment.PaymentTransaction;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two deliveries of one charge request handled at the same moment, through
 * the real listener, as during a rebalance when two instances briefly both
 * have the partition. A second consumer group stands in for the second
 * instance, and a barrier in the authorizer holds both until each is past its
 * lookup, so both inserts really race (DESIGN.md Section 2).
 *
 * <p>The loser must answer from the winner's row at once, in a new
 * transaction: exactly two calls, no retry. If the listener ran inside a
 * transaction, PostgreSQL would abort it at the failed insert, the error
 * handler would retry the record, and a third call would show it. H2 does not
 * abort the transaction, so there the mutation shows up only at the commit
 * (an UnexpectedRollbackException, also retried).
 */
@EmbeddedKafka(
        topics = {PaymentRequestKafkaTests.CHARGE_TOPIC, PaymentRequestKafkaTests.REFUND_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "basketry.payment-requests.retry-initial=5ms",
        "basketry.payment-requests.retry-max=20ms"})
class ListenerRaceTests extends PaymentServiceTestBase {

    private static final String SECOND_INSTANCE = "second-instance";
    private static final AtomicLong NEXT_ORDER = new AtomicLong(5000);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoSpyBean
    private CardAuthorizer spiedAuthorizer;

    @MockitoSpyBean
    private PaymentService spiedPayments;

    private final CyclicBarrier bothInside = new CyclicBarrier(2);
    private final Map<Long, AtomicInteger> callsPerOrder = new ConcurrentHashMap<>();

    /** The same listener bean, reached by a consumer in another group. */
    @TestConfiguration
    static class SecondInstance {

        private final PaymentRequestListener listener;

        SecondInstance(PaymentRequestListener listener) {
            this.listener = listener;
        }

        @KafkaListener(id = SECOND_INSTANCE, groupId = "payment-service-second-instance",
                topics = "${basketry.payment-requests.charge-topic}")
        public void onChargeRequest(ConsumerRecord<String, String> record) {
            listener.onChargeRequest(record);
        }
    }

    @BeforeEach
    void holdBothDeliveriesAtTheAuthorizer() {
        for (MessageListenerContainer container : List.of(registry.getListenerContainer("charge-requests"),
                registry.getListenerContainer(SECOND_INSTANCE))) {
            ContainerTestUtils.waitForAssignment(container, 3);
        }
        doAnswer(call -> {
            bothInside.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(spiedAuthorizer).responseCode(anyString());
        doAnswer(call -> {
            callsPerOrder.computeIfAbsent(call.<ChargeCommand>getArgument(0).orderId(), order -> new AtomicInteger())
                    .incrementAndGet();
            return call.callRealMethod();
        }).when(spiedPayments).authorize(any());
    }

    @RepeatedTest(20)
    void bothDeliveriesAnswerFromOneRowWithoutARetry() throws Exception {
        long order = NEXT_ORDER.incrementAndGet();
        String key = Long.toString(order);

        kafka.send(PaymentRequestKafkaTests.CHARGE_TOPIC, key,
                PaymentRequestKafkaTests.chargeRequest(order, 3, "25.50", APPROVED_CARD)).get();

        await().atMost(PATIENCE).until(() -> outboxRows().stream()
                .filter(row -> row.getMessageKey().equals(key)).count() >= 2);
        List<PaymentTransaction> rows = transactions.findAll().stream()
                .filter(row -> row.getOrderId() == order).toList();
        assertThat(rows).hasSize(1);
        assertThat(outboxRows()).filteredOn(row -> row.getMessageKey().equals(key)).hasSize(2)
                .extracting(OutboxEvent::getPayload)
                .allSatisfy(payload -> assertThat(payload).contains(rows.getFirst().getTransactionRef()));
        assertThat(callsPerOrder.get(order)).as("calls of authorize: 2 means no retry").hasValue(2);
    }
}
