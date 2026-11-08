package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.basketry.payment.outbox.OutboxRelay;
import com.basketry.payment.outbox.ResultPublisher;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * No transaction is open when the listener calls PaymentService, or while the
 * relay sends (DESIGN.md Section 5). PaymentService needs a new transaction
 * after a lost race, because PostgreSQL aborts a transaction after a failed
 * statement; an outer one would be joined instead. The race tests alone would
 * not notice: the abort is a database error, retried, and the retry succeeds.
 */
@EmbeddedKafka(
        topics = {PaymentRequestKafkaTests.CHARGE_TOPIC, PaymentRequestKafkaTests.REFUND_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = "basketry.payment-requests.consumer.enabled=true")
class TransactionBoundaryTests extends PaymentServiceTestBase {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private OutboxRelay relay;

    @MockitoSpyBean
    private PaymentService spiedPayments;

    @MockitoSpyBean
    private ResultPublisher spiedPublisher;

    /** Whether a transaction was open at each call. */
    private final List<Boolean> seen = new CopyOnWriteArrayList<>();

    private Object record(org.mockito.invocation.InvocationOnMock call) throws Throwable {
        seen.add(TransactionSynchronizationManager.isActualTransactionActive());
        return call.callRealMethod();
    }

    @Test
    void theListenerCallsPaymentServiceOutsideAnyTransaction() throws Exception {
        doAnswer(this::record).when(spiedPayments).authorize(any());
        doAnswer(this::record).when(spiedPayments).refund(any());
        Charged charged = (Charged) payments.authorize(charge(51, 3, "25.50", APPROVED_CARD));
        seen.clear();

        kafka.send(PaymentRequestKafkaTests.CHARGE_TOPIC, "52",
                PaymentRequestKafkaTests.chargeRequest(52, 3, "25.50", APPROVED_CARD)).get();
        kafka.send(PaymentRequestKafkaTests.REFUND_TOPIC, "51",
                PaymentRequestKafkaTests.refundRequest(51, 3, charged.transactionRef())).get();

        await().atMost(PATIENCE).until(() -> seen.size() == 2);
        assertThat(seen).containsExactly(false, false);
    }

    @Test
    void theRelaySendsOutsideAnyTransaction() {
        doAnswer(this::record).when(spiedPublisher).publish(anyString(), anyString(), anyString());
        payments.authorize(charge(53, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(54, 3, "25.50", APPROVED_CARD));

        assertThat(relay.publishPending()).isTrue();

        assertThat(seen).containsExactly(false, false);
    }
}
