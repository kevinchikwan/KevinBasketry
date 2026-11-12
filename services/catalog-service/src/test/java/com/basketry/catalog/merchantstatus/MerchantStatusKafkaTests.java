package com.basketry.catalog.merchantstatus;

import static com.basketry.catalog.merchantstatus.StatusEvents.DLT;
import static com.basketry.catalog.merchantstatus.StatusEvents.TOPIC;
import static com.basketry.catalog.merchantstatus.StatusEvents.event;
import static com.basketry.catalog.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.basketry.catalog.cache.CacheKeys;
import com.basketry.catalog.cache.ProductListCache;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TopicReader;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.stubbing.Stubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The real listener and error handler against a real broker (embedded, so no
 * Docker): an event in, the listing changed. The topic is created here, as
 * merchant-service would; the dead-letter topic only if the service creates it.
 * Every test uses its own merchants, because the context is shared, and waits
 * until its last record is applied, so nothing of it is still writing when the
 * next test empties the tables. Redis failures are made short-lived here, so
 * one test's outage never makes another skip the cache.
 */
@EmbeddedKafka(
        topics = StatusEvents.TOPIC,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "basketry.merchant-status.retry-initial=5ms",
        "basketry.merchant-status.retry-max=20ms",
        "basketry.cache.skip-after-failure=1ms"})
@ExtendWith(OutputCaptureExtension.class)
class MerchantStatusKafkaTests extends CatalogServiceTestBase {

    private static final AtomicLong NEXT_MERCHANT = new AtomicLong(1000);

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
    private MerchantStatusService spiedStatuses;

    @MockitoSpyBean
    private ProductListCache spiedCache;

    @MockitoSpyBean
    private KafkaAdmin spiedAdmin;

    private static long newMerchant() {
        return NEXT_MERCHANT.incrementAndGet();
    }

    private void send(long merchantId, String value) throws Exception {
        kafka.send(TOPIC, Long.toString(merchantId), value).get();
    }

    private Optional<MerchantStatus> statusOf(long merchantId) {
        return merchantStatusRepository.findById(merchantId);
    }

    private void awaitVersion(long merchantId, long version) {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(statusOf(merchantId))
                .hasValueSatisfying(status -> assertThat(status.getVersion()).isEqualTo(version)));
    }

    private long versionOf(String scope) {
        return cache.version(scope);
    }

    /** Every dead-letter header about the failure: only our two may be there. */
    private static List<String> exceptionHeaders(ConsumerRecord<String, String> record) {
        return StreamSupport.stream(record.headers().spliterator(), false)
                .map(Header::key).filter(key -> key.startsWith("kafka_dlt-exception")).toList();
    }

    // ---- an event in, the listing changed ------------------------------------------

    /**
     * The listing was cached before the event, so the products vanishing proves
     * the invalidation, not a cache miss. Both scopes are bumped exactly once.
     * Mutation: invalidate only the merchant's own scope (the cached "all"
     * listing would still show the products).
     */
    @Test
    void aDeactivationHidesTheMerchantsProductsFromTheCachedListings() throws Exception {
        long merchant = newMerchant();
        givenProduct(merchant, "Tea");
        assertThat(names(publicListing())).containsExactly("Tea");
        assertThat(names(publicListingOf(merchant))).containsExactly("Tea");
        long all = versionOf(CacheKeys.ALL);
        long own = versionOf(CacheKeys.merchantScope(merchant));

        send(merchant, event(merchant, false, 1));
        awaitVersion(merchant, 1);

        assertThat(names(publicListing())).isEmpty();
        assertThat(names(publicListingOf(merchant))).isEmpty();
        assertThat(versionOf(CacheKeys.ALL)).isEqualTo(all + 1);
        assertThat(versionOf(CacheKeys.merchantScope(merchant))).isEqualTo(own + 1);
    }

    /**
     * Review test 1: a newer active:true event shows the products again.
     * Mutation: in the listener, apply an event only when it is a deactivation.
     */
    @Test
    void aNewerActivationShowsTheProductsAgain() throws Exception {
        long merchant = newMerchant();
        givenProduct(merchant, "Tea");
        send(merchant, event(merchant, false, 1));
        awaitVersion(merchant, 1);
        assertThat(names(publicListingOf(merchant))).isEmpty();

        send(merchant, event(merchant, true, 2));
        awaitVersion(merchant, 2);

        assertThat(statusOf(merchant).orElseThrow().isActive()).isTrue();
        assertThat(names(publicListingOf(merchant))).containsExactly("Tea");
        assertThat(names(publicListing())).containsExactly("Tea");
    }

    /**
     * Review test 2: the status of a merchant with no products is stored, and
     * hides a product created afterwards. Mutation: drop the MERGE's NOT
     * MATCHED branch (no row is inserted, so the merchant stays active at
     * version 0).
     */
    @Test
    void aDeactivationBeforeAnyProductHidesProductsCreatedLater() throws Exception {
        long merchant = newMerchant();
        send(merchant, event(merchant, false, 1));
        awaitVersion(merchant, 1);

        givenProduct(merchant, "Tea");

        assertThat(names(publicListingOf(merchant))).isEmpty();
        assertThat(names(publicListing())).isEmpty();
    }

    /**
     * A duplicate and an older event change nothing and invalidate nothing.
     * The marker event behind them shows they were consumed. Mutation: call
     * merchantChanged whatever the MERGE returned.
     */
    @Test
    void aDuplicateOrAnOlderEventInvalidatesNothing() throws Exception {
        long merchant = newMerchant();
        long marker = newMerchant();
        givenProduct(merchant, "Tea");
        send(merchant, event(merchant, false, 3));
        awaitVersion(merchant, 3);
        long own = versionOf(CacheKeys.merchantScope(merchant));

        send(merchant, event(merchant, false, 3));
        send(merchant, event(merchant, true, 2));
        // Same key, same partition: once the marker is applied, both were consumed.
        kafka.send(TOPIC, Long.toString(merchant), event(marker, false, 1)).get();
        awaitVersion(marker, 1);

        assertThat(statusOf(merchant).orElseThrow().isActive()).isFalse();
        assertThat(statusOf(merchant).orElseThrow().getVersion()).isEqualTo(3);
        assertThat(versionOf(CacheKeys.merchantScope(merchant))).isEqualTo(own);
        assertThat(names(publicListingOf(merchant))).isEmpty();
    }

    // ---- the dead-letter topic ----------------------------------------------------

    /**
     * Every record that can never be applied goes to catalog-service's own
     * dead-letter topic with its exact bytes, its key and only our two
     * exception headers, changes nothing, and the record behind it is applied.
     * Mutation: keep Spring's standard exception headers (the stack trace and
     * the cause would be there too).
     */
    @Test
    void badRecordsAreDeadLetteredWithTheirExactBytesAndOnlyOurTwoHeaders() throws Exception {
        long merchant = newMerchant();
        List<String> bad = List.of(
                "not json",
                event(merchant, false, 1).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                event(merchant, false, 1).replace("\"active\":false", "\"active\":\"false\""),
                event(merchant, false, 1).replace("\"version\":1", "\"version\":1.0"),
                event(merchant, false, 1).replace("\"version\":1", "\"version\":0"),
                event(merchant, false, 1).replace("\"active\":false", "\"active\":false,\"active\":true"),
                event(merchant, false, 1) + "{}");
        for (String value : bad) {
            send(merchant, value);
        }
        send(merchant, null);
        send(merchant, event(merchant, false, 1));

        List<ConsumerRecord<String, String>> deadLetters = TopicReader.readKey(broker, DLT,
                Long.toString(merchant), bad.size() + 1);
        assertThat(deadLetters).extracting(ConsumerRecord::value)
                .containsExactlyElementsOf(Stream.concat(bad.stream(), Stream.of((String) null)).toList());
        for (ConsumerRecord<String, String> deadLetter : deadLetters) {
            assertThat(exceptionHeaders(deadLetter)).containsExactlyInAnyOrder(
                    KafkaHeaders.DLT_EXCEPTION_FQCN, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(InvalidMerchantStatusEventException.class.getName());
            assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        }
        assertThat(deadLetters).extracting(record -> TopicReader.header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .containsExactly(
                        "The value is not valid JSON (line 1, column 1)",
                        "schemaVersion is missing or not 1",
                        "active must be a JSON boolean",
                        "version must be a whole JSON number that fits 64 bits",
                        "version must be positive",
                        "The value is not valid JSON (line 1, column 136)",
                        "The value is not valid JSON (line 1, column 180)",
                        "The record has no value");
        // The good record behind them all is applied.
        awaitVersion(merchant, 1);
        assertThat(statusOf(merchant).orElseThrow().isActive()).isFalse();
    }

    /**
     * A dead letter goes to the partition its key goes to, so one merchant's
     * dead letters stay in order and a replay goes back to the right partition.
     * Six merchants cover all three partitions. Mutation: a fixed partition 0
     * for the dead letter instead of -1.
     */
    @Test
    void aDeadLetterKeepsItsKeysPartition() throws Exception {
        List<Long> merchants = Stream.generate(MerchantStatusKafkaTests::newMerchant).limit(6).toList();
        for (long merchant : merchants) {
            send(merchant, "not json " + merchant);
        }

        List<ConsumerRecord<String, String>> deadLetters = TopicReader.read(broker, DLT,
                record -> record.value() != null && record.value().startsWith("not json ")
                        && merchants.contains(Long.parseLong(record.key())), merchants.size());
        assertThat(deadLetters).allSatisfy(deadLetter -> assertThat(deadLetter.partition())
                .isEqualTo(ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value())
                        .getInt()));
        assertThat(deadLetters.stream().map(ConsumerRecord::partition).distinct().count())
                .as("partitions used").isGreaterThan(1);
    }

    /**
     * A bug is not a database failure: it must not block the partition, and
     * its message is not ours to quote. Mutation: safeMessage returns the
     * exception's own message.
     */
    @Test
    void anUnexpectedFailureIsDeadLetteredWithoutItsMessage() throws Exception {
        long merchant = newMerchant();
        doThrow(new IllegalStateException("a bug that quotes the record"))
                .when(spiedStatuses).apply(eq(merchant), anyBoolean(), eq(1L));

        send(merchant, event(merchant, false, 1));
        send(merchant, event(merchant, false, 2));

        ConsumerRecord<String, String> deadLetter = TopicReader.readKey(broker, DLT, Long.toString(merchant), 1)
                .getFirst();
        assertThat(exceptionHeaders(deadLetter)).containsExactlyInAnyOrder(
                KafkaHeaders.DLT_EXCEPTION_FQCN, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(TopicReader.header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo(MerchantStatusConsumerConfig.UNEXPECTED);
        awaitVersion(merchant, 2);
    }

    /**
     * The dead-letter publish failing (here: the topic cannot be checked) keeps
     * the record. Mutation: in the recoverer, log and return instead of
     * throwing when admin.initialize() fails.
     */
    @Test
    void aFailedDeadLetterPublishIsTriedAgainNotDropped() throws Exception {
        long merchant = newMerchant();
        AtomicInteger refusals = new AtomicInteger();
        doAnswer(call -> refusals.getAndIncrement() == 0 ? false : call.callRealMethod())
                .when(spiedAdmin).initialize();

        send(merchant, "not json, kept");

        assertThat(TopicReader.readKey(broker, DLT, Long.toString(merchant), 1))
                .extracting(ConsumerRecord::value).containsExactly("not json, kept");
        assertThat(refusals).hasValueGreaterThan(1);
    }

    // ---- outages: retried without limit, or never an error --------------------------

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
        long merchant = newMerchant();
        Stubber failures = doThrow(new IllegalStateException("wrapped",
                new DataAccessResourceFailureException("database down")));
        for (int failure = 2; failure <= 16; failure++) {
            failures = failures.doThrow(new CannotCreateTransactionException("database down"));
        }
        failures.doCallRealMethod().when(spiedStatuses).apply(eq(merchant), anyBoolean(), anyLong());

        send(merchant, event(merchant, false, 1));

        awaitVersion(merchant, 1);
        verify(spiedStatuses, atLeast(17)).apply(eq(merchant), anyBoolean(), anyLong());
        assertNoDeadLetter(merchant);
    }

    /**
     * Two instances inserting the same new merchant at once: on PostgreSQL one
     * fails on the primary key (MerchantStatusRepository#apply). That is a
     * non-transient database error, retried like any other, and the retry takes
     * the MATCHED branch. Mutation: retry only TransientDataAccessException.
     */
    @Test
    void aPrimaryKeyViolationIsRetriedAndThenApplied() throws Exception {
        long merchant = newMerchant();
        doThrow(new DuplicateKeyException("duplicate key value violates unique constraint"))
                .doCallRealMethod().when(spiedStatuses).apply(eq(merchant), anyBoolean(), anyLong());

        send(merchant, event(merchant, false, 1));

        awaitVersion(merchant, 1);
        assertNoDeadLetter(merchant);
    }

    /**
     * Redis failing on every bump: the event is still applied, the offset
     * committed (the next event is applied too), a WARN says the listings may
     * be stale, and nothing is retried or dead-lettered. Mutation: the guard's
     * bump rethrows instead of returning false.
     */
    @Test
    void redisDownWhileApplyingIsAWarningNotAFailure(CapturedOutput output) throws Exception {
        long merchant = newMerchant();
        doThrow(new RedisConnectionFailureException("Redis is down")).when(spiedCache).bump(anyString());

        send(merchant, event(merchant, false, 1));
        send(merchant, event(merchant, true, 2));

        awaitVersion(merchant, 2);
        verify(spiedStatuses, times(1)).apply(merchant, false, 1);
        assertThat(output).contains("Could not invalidate the cached listings of merchant " + merchant);
        assertNoDeadLetter(merchant);
    }

    // ---- settings -----------------------------------------------------------------

    @Test
    void theListenerIsOneThreadInTheCatalogServiceGroup() {
        assertThat(registry.getListenerContainers()).hasSize(1);
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
        assertThat(container.getGroupId()).isEqualTo("catalog-service");
        assertThat(container.getContainerProperties().getTopics()).containsExactly(TOPIC);
        assertThat(container.getConcurrency()).isEqualTo(1);
    }

    /** About 1 s a record with a slow Redis: 50 a poll stays inside the 5-minute poll interval. */
    @Test
    void aPollTakesAtMostFiftyRecordsAndReadsFromTheEarliestOffset() {
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
    }

    /**
     * merchant.status.changed is merchant-service's and identity-service's
     * dead-letter topic is identity-service's: catalog-service declares only its own.
     */
    @Test
    void theServiceDeclaresOnlyItsOwnDeadLetterTopic() {
        assertThat(context.getBeansOfType(NewTopic.class).values()).singleElement()
                .satisfies(topic -> {
                    assertThat(topic.name()).isEqualTo(DLT);
                    assertThat(topic.numPartitions()).isEqualTo(3);
                });
    }

    /** A marker behind everything else for this key shows that the topic was read to the end. */
    private void assertNoDeadLetter(long merchant) throws Exception {
        // The dead-letter topic exists only once something was dead-lettered.
        assertThat(spiedAdmin.initialize()).isTrue();
        kafka.send(DLT, Long.toString(merchant), "marker").get();
        assertThat(TopicReader.readKey(broker, DLT, Long.toString(merchant), 1))
                .extracting(ConsumerRecord::value).containsExactly("marker");
    }
}
