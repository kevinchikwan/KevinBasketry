package com.basketry.identity.merchantstatus;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Stubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The real listener and error handler against a real broker (embedded, so no
 * Docker). Like the Compose broker, this one does not create topics on its own,
 * so the dead-letter topic exists only if the service creates it.
 */
@EmbeddedKafka(
        topics = MerchantStatusKafkaTests.TOPIC,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@SpringBootTest(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "basketry.merchant-status.retry-initial=5ms",
        "basketry.merchant-status.retry-max=20ms"})
class MerchantStatusKafkaTests {

    static final String TOPIC = "merchant.status.changed";
    private static final String DEAD_LETTERS = "merchant.status.changed.DLT";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final String PASSWORD = "Corr3ct-Horse!";
    private static final AtomicLong NEXT_MERCHANT = new AtomicLong(1000);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private MerchantLinkService linkService;

    @MockitoSpyBean
    private MerchantStatusHandler handler;

    private long merchant;
    private String key;

    @BeforeEach
    void linkAnAccount() {
        merchant = NEXT_MERCHANT.incrementAndGet();
        key = Long.toString(merchant);
        String email = "shop" + merchant + "@example.com";
        registrationService.registerBuyer(email, PASSWORD);
        linkService.link(email, merchant, null);
    }

    private void publish(String recordKey, String value) throws Exception {
        kafka.send(TOPIC, recordKey, value).get();
    }

    private Account account() {
        return accounts.findByMerchantId(merchant).orElseThrow();
    }

    private void awaitVersion(long version) {
        await().atMost(PATIENCE).untilAsserted(
                () -> assertThat(account().getMerchantStatusVersion()).isEqualTo(version));
    }

    /** Everything in the dead-letter topic for this key, once at least {@code expected} records are there. */
    private List<ConsumerRecord<String, String>> deadLetters(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 200);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DEAD_LETTERS));
            await().atMost(PATIENCE).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        records.add(record);
                    }
                });
                return records.size() >= expected;
            });
        }
        return records;
    }

    private void assertNoDeadLetters() {
        // A marker behind everything else for this key shows that the topic was read to the end.
        kafka.send(DEAD_LETTERS, key, "marker").join();
        assertThat(deadLetters(1)).extracting(ConsumerRecord::value).containsExactly("marker");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void aPublishedEventReachesTheAccount() throws Exception {
        publish(key, event(merchant, false, 1));

        awaitVersion(1);
        assertThat(account().isMerchantActive()).isFalse();
    }

    @Test
    void duplicatesAndOutOfOrderDeliveryEndAtTheNewestEvent() throws Exception {
        String second = event(merchant, true, 2);
        publish(key, second);
        publish(key, event(merchant, false, 1));
        publish(key, second);
        publish(key, event(merchant, false, 3));

        awaitVersion(3);
        assertThat(account().isMerchantActive()).isFalse();
    }

    /** Same partition as the event behind it, which is applied: the skip moved on. */
    @Test
    void anEventWithNoLinkedAccountIsSkippedAndNotDeadLettered() throws Exception {
        int partition = 0;
        long unknown = merchant + 1_000_000;
        kafka.send(TOPIC, partition, key, event(unknown, false, 1)).get();
        kafka.send(TOPIC, partition, key, event(merchant, false, 1)).get();

        awaitVersion(1);
        assertNoDeadLetters();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "{\"schemaVersion\":2,\"merchantId\":1,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":MERCHANT,\"active\":\"false\",\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":MERCHANT,\"active\":1,\"version\":9}"})
    void aBadRecordGoesToTheDeadLetterTopicAndTheNextOneIsApplied(String template) throws Exception {
        String bad = template.replace("MERCHANT", key);

        publish(key, bad);
        publish(key, event(merchant, false, 1));

        awaitVersion(1);
        assertThat(account().isMerchantActive()).isFalse();
        ConsumerRecord<String, String> deadLetter = deadLetters(1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(bad);
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
    }

    @Test
    void aRecordWithoutAValueGoesToTheDeadLetterTopic() throws Exception {
        publish(key, null);
        publish(key, event(merchant, false, 1));

        awaitVersion(1);
        assertThat(deadLetters(1).getFirst().value()).isNull();
    }

    /** More failures than the error handler's default limit of 9 retries. */
    @Test
    void aDatabaseFailureIsRetriedWithoutLimitAndNothingIsLostOrReordered() throws Exception {
        String first = event(merchant, false, 1);
        Stubber failures = doThrow(new DataAccessResourceFailureException("database down"));
        for (int failure = 1; failure < 40; failure++) {
            failures = failures.doThrow(new CannotCreateTransactionException("database down"));
        }
        failures.doCallRealMethod().when(handler).handle(first);

        publish(key, first);
        publish(key, event(merchant, true, 2));

        awaitVersion(2);
        assertThat(account().isMerchantActive()).isTrue();
        verify(handler, atLeast(41)).handle(first);
        assertNoDeadLetters();
    }

    /** A bug is not a database failure: it must not block the partition. */
    @Test
    void anUnexpectedFailureGoesToTheDeadLetterTopicInsteadOfBlocking() throws Exception {
        String first = event(merchant, false, 1);
        doThrow(new IllegalStateException("a bug")).when(handler).handle(first);

        publish(key, first);
        publish(key, event(merchant, true, 2));

        awaitVersion(2);
        assertThat(deadLetters(1).getFirst().value()).isEqualTo(first);
    }
}
