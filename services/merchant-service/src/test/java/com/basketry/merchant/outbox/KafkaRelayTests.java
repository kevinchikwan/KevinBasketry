package com.basketry.merchant.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.support.MerchantServiceTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The whole path against a real broker (embedded, so no Docker): PATCH, the
 * scheduled relay, the topic the service creates for itself, and a consumer.
 * Like the Compose broker, this one does not create topics on its own.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms"})
class KafkaRelayTests extends MerchantServiceTestBase {

    private static final String TOPIC = "merchant.status.changed";

    @Autowired
    private EmbeddedKafkaBroker broker;

    private void awaitAllSent() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(outbox.findAll())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
    }

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }

    /** Every record for these keys, in the order the consumer got them. */
    private List<ConsumerRecord<String, String>> read(int expected, Set<String> keys) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (keys.contains(record.key())) {
                        records.add(record);
                    }
                });
                return records.size() >= expected;
            });
        }
        return records;
    }

    @Test
    void aPatchReachesTheTopicAndTheRowIsMarked() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false}").andExpect(status().isOk());
        awaitAllSent();

        String key = merchant.getId().toString();
        ConsumerRecord<String, String> record = read(1, Set.of(key)).getFirst();
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.headers().toArray()).isEmpty();
        JsonNode payload = JSON.readTree(record.value());
        assertThat(payload.get("merchantId").asLong()).isEqualTo(merchant.getId());
        assertThat(payload.get("merchantCode").asString()).isEqualTo(merchant.getMerchantCode());
        assertThat(payload.get("active").asBoolean()).isFalse();
        assertThat(payload.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void theServiceCreatedTheTopicWithThreePartitions() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        patchAsAdmin(merchant, "{\"active\":false}").andExpect(status().isOk());
        awaitAllSent();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            assertThat(consumer.partitionsFor(TOPIC)).hasSize(3);
        }
    }

    @Test
    void eachMerchantsEventsArriveInVersionOrderOnOnePartition() throws Exception {
        Merchant first = saveLinked("first@example.com");
        Merchant second = saveLinked("second@example.com");
        int changes = 10;
        boolean value = false;
        for (int change = 0; change < changes; change++) {
            patchAsAdmin(first, "{\"active\":" + value + "}").andExpect(status().isOk());
            patchAsAdmin(second, "{\"active\":" + value + "}").andExpect(status().isOk());
            value = !value;
        }
        awaitAllSent();

        String firstKey = first.getId().toString();
        String secondKey = second.getId().toString();
        List<ConsumerRecord<String, String>> records = read(2 * changes, Set.of(firstKey, secondKey));

        for (String key : List.of(firstKey, secondKey)) {
            List<Long> versions = new ArrayList<>();
            Set<Integer> partitions = new HashSet<>();
            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(key)) {
                    versions.add(JSON.readTree(record.value()).get("version").asLong());
                    partitions.add(record.partition());
                }
            }
            assertThat(versions).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
            assertThat(partitions).hasSize(1);
        }
    }
}
