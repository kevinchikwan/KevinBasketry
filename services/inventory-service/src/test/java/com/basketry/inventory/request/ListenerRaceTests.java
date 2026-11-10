package com.basketry.inventory.request;

import static com.basketry.inventory.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.basketry.inventory.counter.StockCounterGuard;
import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.outbox.StockResultEvents;
import com.basketry.inventory.stock.ReservationStatus;
import com.basketry.inventory.stock.ReserveCommand;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InventoryServiceTestBase;
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
 * Two instances of the service receive the same reserve, as after a rebalance
 * in which both briefly have the partition. A second consumer group stands in
 * for the second instance, and a barrier in the counter guard's take holds both
 * until each is past its stored-row lookup, so both really race to the gate
 * (DESIGN.md Section 2).
 *
 * <p>Both take from the counter; the loser's insert hits the primary key, which
 * rolls back its PostgreSQL decrement, it gives its counter take back, and it
 * answers from the winner's row at once, in a new transaction: exactly two
 * calls, no retry. If the listener ran inside a transaction, the error handler
 * would retry the record (PostgreSQL aborts the transaction at the failed
 * insert; H2 shows it at the commit), and a third call would show it.
 */
@EmbeddedKafka(
        topics = {StockRequestKafkaTests.RESERVE_TOPIC, StockRequestKafkaTests.RELEASE_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.stock-requests.consumer.enabled=true",
        "basketry.stock-requests.retry-initial=5ms",
        "basketry.stock-requests.retry-max=20ms"})
class ListenerRaceTests extends InventoryServiceTestBase {

    private static final String SECOND_INSTANCE = "second-instance";
    private static final AtomicLong NEXT_ORDER = new AtomicLong(5000);
    private static final long PRODUCT = 42;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoSpyBean
    private StockCounterGuard spiedGuard;

    @MockitoSpyBean
    private StockService spiedStock;

    private final CyclicBarrier bothInside = new CyclicBarrier(2);
    private final Map<Long, AtomicInteger> callsPerOrder = new ConcurrentHashMap<>();

    /** The same listener bean, reached by a consumer in another group. */
    @TestConfiguration
    static class SecondInstance {

        private final StockRequestListener listener;

        SecondInstance(StockRequestListener listener) {
            this.listener = listener;
        }

        @KafkaListener(id = SECOND_INSTANCE, groupId = "inventory-service-second-instance",
                topics = "${basketry.stock-requests.reserve-topic}")
        public void onReserveRequest(ConsumerRecord<String, String> record) {
            listener.onReserveRequest(record);
        }
    }

    @BeforeEach
    void holdBothDeliveriesAtTheCounter() {
        // The release listener too: it is in the reserve listener's group, so until it has
        // joined, its arrival can revoke the reserve partitions in the middle of a repetition.
        for (MessageListenerContainer container : List.of(registry.getListenerContainer("reserve-requests"),
                registry.getListenerContainer("release-requests"), registry.getListenerContainer(SECOND_INSTANCE))) {
            ContainerTestUtils.waitForAssignment(container, 3);
        }
        givenStockAndCounter(PRODUCT, 10);
        doAnswer(call -> {
            bothInside.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(spiedGuard).take(anyLong(), anyInt());
        doAnswer(call -> {
            callsPerOrder.computeIfAbsent(call.<ReserveCommand>getArgument(0).orderId(), order -> new AtomicInteger())
                    .incrementAndGet();
            return call.callRealMethod();
        }).when(spiedStock).reserve(any());
    }

    @RepeatedTest(20)
    void bothDeliveriesAnswerFromOneRowWithoutARetry() throws Exception {
        long order = NEXT_ORDER.incrementAndGet();
        String key = Long.toString(order);

        kafka.send(StockRequestKafkaTests.RESERVE_TOPIC, key,
                StockRequestKafkaTests.request(order, PRODUCT, "3")).get();

        await().atMost(PATIENCE).until(() -> outboxRows().stream()
                .filter(row -> row.getMessageKey().equals(key)).count() >= 2);
        // Both took 3 from the counter; the loser gave its 3 back before storing its answer.
        assertThat(counterOf(PRODUCT)).hasValue(7);
        assertThat(reservation(order, PRODUCT).getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(quantityOf(PRODUCT)).isEqualTo(7);
        assertThat(outboxRows()).filteredOn(row -> row.getMessageKey().equals(key)).hasSize(2)
                .extracting(OutboxEvent::getTopic).containsOnly(StockResultEvents.RESERVED_TOPIC);
        assertThat(callsPerOrder.get(order)).as("calls of reserve: 2 means no retry").hasValue(2);
    }
}
