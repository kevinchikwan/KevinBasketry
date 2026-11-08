package com.basketry.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaUtils;

/**
 * How Spring Kafka writes a record into a log line: its place only, never its
 * value. A charge request's value holds the raw card number, and so does its
 * dead letter. Spring's default for a producer record is its
 * {@code toString()}, value included, which it logs when sending at TRACE
 * level. The consumer default is already topic-partition@offset; it is set
 * here too, so the rule does not depend on a default.
 *
 * <p>This does not cover the Kafka client's own TRACE logging
 * ({@code org.apache.kafka.clients}), which prints whole records: never run it
 * at TRACE (DESIGN.md Section 5).
 */
@Configuration(proxyBeanMethods = false)
class KafkaRecordLogging {

    KafkaRecordLogging() {
        KafkaUtils.setProducerRecordFormatter(record -> record.topic() + "-" + record.partition());
        KafkaUtils.setConsumerRecordFormatter(
                record -> record.topic() + "-" + record.partition() + "@" + record.offset());
    }
}
