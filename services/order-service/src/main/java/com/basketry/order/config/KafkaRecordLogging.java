package com.basketry.order.config;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.ProducerListener;

/**
 * How Spring Kafka writes a record into a log line: its place only, never its
 * value. A charge request's value holds the raw card number (DESIGN.md Section
 * 2, order-service, the card number).
 *
 * <ul>
 *   <li>The record formatter: Spring's default for a producer record is its
 *       {@code toString()}, value included, which KafkaTemplate logs when
 *       sending at TRACE level.</li>
 *   <li>The producer listener: Spring Boot's default, LoggingProducerListener,
 *       logs up to 100 characters of the value at ERROR when a send fails. This
 *       one logs the topic and the key (the orderId) only. Boot uses it because
 *       it is a ProducerListener bean.</li>
 * </ul>
 *
 * <p>Neither covers the Kafka client's own TRACE logging
 * ({@code org.apache.kafka.clients}), which prints whole records: never run it
 * at TRACE (DESIGN.md Section 5).
 */
@Configuration(proxyBeanMethods = false)
class KafkaRecordLogging {

    private static final Logger log = LoggerFactory.getLogger(KafkaRecordLogging.class);

    KafkaRecordLogging() {
        KafkaUtils.setProducerRecordFormatter(record -> record.topic() + "-" + record.partition());
        KafkaUtils.setConsumerRecordFormatter(
                record -> record.topic() + "-" + record.partition() + "@" + record.offset());
    }

    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {
        return new ProducerListener<>() {
            @Override
            public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata, Exception failure) {
                log.error("Could not send a record to {} (key {}): {}", record.topic(), record.key(),
                        failure.getClass().getName());
            }
        };
    }
}
