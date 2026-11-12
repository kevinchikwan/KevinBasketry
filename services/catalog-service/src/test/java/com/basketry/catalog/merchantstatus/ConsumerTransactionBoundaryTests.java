package com.basketry.catalog.merchantstatus;

import static com.basketry.catalog.merchantstatus.StatusEvents.event;
import static com.basketry.catalog.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.basketry.catalog.cache.ListingInvalidator;
import com.basketry.catalog.support.CatalogServiceTestBase;
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
 * No transaction is open when the listener calls MerchantStatusService, nor
 * when the listings are invalidated (DESIGN.md Section 5, the catalog-service
 * consumer). ListingInvalidator refuses to run inside a transaction, so an
 * outer one would make every status change fail, roll its MERGE back and go to
 * the dead-letter topic. This probe says why at once, instead of the other
 * tests failing on a missing status.
 *
 * <p>Mutation: annotate MerchantStatusListener#onRecord with
 * {@code @Transactional}.
 */
@EmbeddedKafka(
        topics = StatusEvents.TOPIC,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = "basketry.merchant-status.consumer.enabled=true")
class ConsumerTransactionBoundaryTests extends CatalogServiceTestBase {

    private static final long MERCHANT_ID = 701;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @MockitoSpyBean
    private MerchantStatusService spiedStatuses;

    @MockitoSpyBean
    private ListingInvalidator spiedInvalidator;

    /** "method: whether a transaction was open", one entry per call. */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    /** Calls that have returned or thrown: the test waits for these before it ends. */
    private final AtomicInteger finished = new AtomicInteger();

    private Object record(InvocationOnMock call) throws Throwable {
        seen.add(call.getMethod().getName() + ": " + TransactionSynchronizationManager.isActualTransactionActive());
        try {
            return call.callRealMethod();
        } finally {
            finished.incrementAndGet();
        }
    }

    @Test
    void theListenerAppliesAndInvalidatesOutsideAnyTransaction() throws Exception {
        givenProduct(MERCHANT_ID, "Tea");
        doAnswer(this::record).when(spiedStatuses).apply(anyLong(), anyBoolean(), anyLong());
        doAnswer(this::record).when(spiedInvalidator).merchantChanged(anyLong());

        kafka.send(StatusEvents.TOPIC, Long.toString(MERCHANT_ID), event(MERCHANT_ID, false, 1)).get();

        await().atMost(PATIENCE).until(() -> finished.get() >= 2);
        assertThat(seen).startsWith("apply: false", "merchantChanged: false");
        assertThat(merchantStatusRepository.findById(MERCHANT_ID)).hasValueSatisfying(status ->
                assertThat(status.isActive()).isFalse());
        assertThat(names(publicListingOf(MERCHANT_ID))).isEmpty();
    }
}
