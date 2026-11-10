package com.basketry.inventory.request;

import static com.basketry.inventory.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.basketry.inventory.counter.StockCounter;
import com.basketry.inventory.outbox.OutboxRelay;
import com.basketry.inventory.outbox.ResultPublisher;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InventoryServiceTestBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * No transaction is open when the listener calls StockService, when
 * StockService calls Redis, or while the relay sends (DESIGN.md Sections 2 and
 * 5). StockService needs a new transaction after a lost race, because
 * PostgreSQL aborts a transaction after a failed statement; an outer one would
 * be joined instead. A Redis call inside a transaction would hold its
 * connection and row locks for up to a Redis timeout. Neither would show in the
 * other tests: the abort is retried and succeeds, and a held lock is only slow.
 *
 * <p>The Redis probe sits on the counter under the guard, and every path of a
 * reserve and a release that reaches it runs here through the listener: not
 * loaded then loaded, taken, "insufficient" then confirmed and subtracted, a
 * failed gate giving its take back, and a release giving stock back.
 */
@EmbeddedKafka(
        topics = {StockRequestKafkaTests.RESERVE_TOPIC, StockRequestKafkaTests.RELEASE_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = "basketry.stock-requests.consumer.enabled=true")
class TransactionBoundaryTests extends InventoryServiceTestBase {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private OutboxRelay relay;

    @MockitoSpyBean
    private StockService spiedStock;

    @MockitoSpyBean
    private StockCounter spiedCounter;

    @MockitoSpyBean
    private ResultPublisher spiedPublisher;

    /** "method: whether a transaction was open", one entry per call. */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    /** Calls that have returned: a test waits for these, not for {@link #seen}, before it ends. */
    private final AtomicInteger finished = new AtomicInteger();

    private Object record(InvocationOnMock call) throws Throwable {
        seen.add(call.getMethod().getName() + ": " + TransactionSynchronizationManager.isActualTransactionActive());
        try {
            return call.callRealMethod();
        } finally {
            finished.incrementAndGet();
        }
    }

    private void send(String topic, long orderId, long productId, int quantity) throws Exception {
        kafka.send(topic, Long.toString(orderId),
                StockRequestKafkaTests.request(orderId, productId, Integer.toString(quantity))).get();
    }

    @Test
    void theListenerCallsStockServiceOutsideAnyTransaction() throws Exception {
        givenStockAndCounter(42, 10);
        doAnswer(this::record).when(spiedStock).reserve(any());
        doAnswer(this::record).when(spiedStock).release(any());

        send(StockRequestKafkaTests.RESERVE_TOPIC, 51, 42, 3);
        await().atMost(PATIENCE).until(() -> seen.size() == 1);
        send(StockRequestKafkaTests.RELEASE_TOPIC, 51, 42, 3);

        // seen counts calls as they start. Waiting for both to return (outbox rows and the
        // release's counter write included) means nothing of this test is still writing when
        // the next test has emptied the tables.
        await().atMost(PATIENCE).until(() -> finished.get() == 2);
        assertThat(seen).containsExactly("reserve: false", "release: false");
        assertThat(outboxRows()).hasSize(2);
    }

    @Test
    void everyRedisCallOfAReserveOrAReleaseIsOutsideAnyTransaction() throws Exception {
        givenStock(61, 10);                 // not loaded: take, load, take
        givenStockAndCounter(62, 10);       // the counter says no, PostgreSQL yes: subtract after the commit
        counter.overwrite(62, 0);
        givenStock(63, 1);                  // the counter says yes, the gate no: give the take back
        counter.overwrite(63, 10);
        doAnswer(this::record).when(spiedCounter).take(anyLong(), anyInt());
        doAnswer(this::record).when(spiedCounter).addIfLoaded(anyLong(), anyLong());
        doAnswer(this::record).when(spiedCounter).loadIfAbsent(anyLong(), anyLong());

        send(StockRequestKafkaTests.RESERVE_TOPIC, 61, 61, 3);
        send(StockRequestKafkaTests.RESERVE_TOPIC, 61, 62, 3);
        send(StockRequestKafkaTests.RESERVE_TOPIC, 61, 63, 3);
        // One key on one topic, so the three reserves are handled in order. The release is
        // another topic and another listener thread: it goes only once the reserves are stored,
        // so it finds the reservation (no tombstone) and its Redis call comes last.
        await().atMost(PATIENCE).until(() -> outboxRows().size() == 3);
        send(StockRequestKafkaTests.RELEASE_TOPIC, 61, 61, 3);   // gives 3 back to a loaded counter

        // Until the 8th call has returned, not merely started: it is the last write of the test.
        await().atMost(PATIENCE).until(() -> finished.get() >= 8);
        assertThat(seen).containsExactly(
                "take: false", "loadIfAbsent: false", "take: false",      // reserve, product 61
                "take: false", "addIfLoaded: false",                      // reserve, product 62
                "take: false", "addIfLoaded: false",                      // reserve, product 63
                "addIfLoaded: false");                                    // release, product 61
        assertThat(quantityOf(61)).isEqualTo(10);
        assertThat(quantityOf(62)).isEqualTo(7);
        assertThat(quantityOf(63)).isEqualTo(1);
    }

    @Test
    void theRelaySendsOutsideAnyTransaction() {
        givenStock(42, 10);
        doAnswer(this::record).when(spiedPublisher).publish(anyString(), anyString(), anyString());
        stock.reserve(reserve(53, 42, 1));
        stock.reserve(reserve(54, 42, 1));

        assertThat(relay.publishPending()).isTrue();

        assertThat(seen).containsExactly("publish: false", "publish: false");
    }
}
