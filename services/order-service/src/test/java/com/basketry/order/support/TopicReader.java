package com.basketry.order.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/** Reads a topic from its beginning with a fresh consumer group, as an outside consumer would. */
public final class TopicReader {

    public static final Duration PATIENCE = Duration.ofSeconds(30);

    private TopicReader() {
    }

    public static KafkaConsumer<String, String> consumer(EmbeddedKafkaBroker broker) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 200);
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }

    /** The records with this key, in the order read, once at least {@code expected} are there. */
    public static List<ConsumerRecord<String, String>> readKey(EmbeddedKafkaBroker broker, String topic, String key,
                                                               int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer(broker)) {
            consumer.subscribe(List.of(topic));
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
}
