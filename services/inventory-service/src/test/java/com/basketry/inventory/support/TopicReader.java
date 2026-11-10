package com.basketry.inventory.support;

import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
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

    /** The matching records, in the order read, once at least {@code expected} are there. */
    public static List<ConsumerRecord<String, String>> read(EmbeddedKafkaBroker broker, String topic,
                                                            Predicate<ConsumerRecord<String, String>> filter,
                                                            int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer(broker)) {
            consumer.subscribe(List.of(topic));
            await().atMost(PATIENCE).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (filter.test(record)) {
                        records.add(record);
                    }
                });
                return records.size() >= expected;
            });
        }
        return records;
    }

    public static List<ConsumerRecord<String, String>> readKey(EmbeddedKafkaBroker broker, String topic,
                                                               String key, int expected) {
        return read(broker, topic, record -> key.equals(record.key()), expected);
    }

    /** Every header as "name=value" text. */
    public static List<String> headers(ConsumerRecord<String, String> record) {
        List<String> headers = new ArrayList<>();
        for (Header header : record.headers()) {
            headers.add(header.key() + "=" + (header.value() == null ? ""
                    : new String(header.value(), StandardCharsets.UTF_8)));
        }
        return headers;
    }

    public static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
