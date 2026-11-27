package com.basketry.promotion.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Reads a topic from its beginning with a fresh consumer group, as an outside
 * consumer would, as bytes: a dead letter keeps the original key and value byte
 * for byte, and some of them are not UTF-8.
 */
public final class TopicReader {

    public static final Duration PATIENCE = Duration.ofSeconds(30);

    private TopicReader() {
    }

    public static KafkaConsumer<byte[], byte[]> consumer(EmbeddedKafkaBroker broker) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 200);
        return new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    /** The records with this key, in the order read, once at least {@code expected} are there. */
    public static List<ConsumerRecord<byte[], byte[]>> readKey(EmbeddedKafkaBroker broker, String topic, byte[] key,
                                                               int expected) {
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(broker)) {
            consumer.subscribe(List.of(topic));
            await().atMost(PATIENCE).until(() -> {
                poll(consumer, key, records);
                return records.size() >= expected;
            });
        }
        return records;
    }

    /** Every record with this key that arrives within {@code wait}: for checking that none does. */
    public static List<ConsumerRecord<byte[], byte[]>> readKeyFor(EmbeddedKafkaBroker broker, String topic, byte[] key,
                                                                  Duration wait) {
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(broker)) {
            consumer.subscribe(List.of(topic));
            Instant end = Instant.now().plus(wait);
            while (Instant.now().isBefore(end)) {
                poll(consumer, key, records);
            }
        }
        return records;
    }

    private static void poll(KafkaConsumer<byte[], byte[]> consumer, byte[] key,
                             List<ConsumerRecord<byte[], byte[]>> records) {
        consumer.poll(Duration.ofMillis(200)).forEach(record -> {
            if (Arrays.equals(key, record.key())) {
                records.add(record);
            }
        });
    }
}
