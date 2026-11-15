package com.basketry.order.results;

import com.basketry.order.saga.SagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One listener per result topic, one thread each (DESIGN.md Section 5,
 * order-service results). A listener only parses the record and hands it to
 * {@link SagaService}, and never runs inside a transaction: SagaService opens
 * its own, with the order's row lock. A record that cannot be parsed or
 * applied goes to the topic's dead-letter topic; a database failure is retried
 * (ResultConsumerConfig). The ids name the containers only: all six share the
 * consumer group order-service (spring.kafka.consumer.group-id), as payment-service's
 * and inventory-service's listeners share theirs.
 */
@Component
@ConditionalOnProperty(name = "basketry.order-results.consumer.enabled", havingValue = "true")
class ResultListener {

    private final ResultParser parser;
    private final SagaService saga;

    ResultListener(ResultParser parser, SagaService saga) {
        this.parser = parser;
        this.saga = saga;
    }

    @KafkaListener(id = "order-results-charged", idIsGroup = false, topics = ResultTopics.CHARGED)
    void onCharged(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "order-results-declined", idIsGroup = false, topics = ResultTopics.DECLINED)
    void onDeclined(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "order-results-refunded", idIsGroup = false, topics = ResultTopics.REFUNDED)
    void onRefunded(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "order-results-reserved", idIsGroup = false, topics = ResultTopics.RESERVED)
    void onReserved(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "order-results-reservation-failed", idIsGroup = false, topics = ResultTopics.RESERVATION_FAILED)
    void onReservationFailed(ConsumerRecord<String, String> record) {
        handle(record);
    }

    @KafkaListener(id = "order-results-released", idIsGroup = false, topics = ResultTopics.RELEASED)
    void onReleased(ConsumerRecord<String, String> record) {
        handle(record);
    }

    private void handle(ConsumerRecord<String, String> record) {
        saga.apply(parser.parse(record.topic(), record.value()));
    }
}
