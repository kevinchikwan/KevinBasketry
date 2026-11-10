package com.basketry.inventory.request;

import com.basketry.inventory.stock.StockService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the two request topics. It only turns a record into a command and
 * calls {@link StockService}; failures are decided in
 * {@link StockRequestConsumerConfig}. The value arrives as a plain string, so a
 * bad record reaches the error handler with its original bytes.
 *
 * <p><b>Never {@code @Transactional}</b>, and the container has no transaction
 * manager: StockService starts its own transactions, needs a new one after a
 * lost race, and calls Redis only outside them (DESIGN.md Section 2).
 * TransactionBoundaryTests checks this, for the database and for Redis.
 *
 * <p>Both listeners are in the one consumer group of the service,
 * {@code spring.kafka.consumer.group-id}: an id is not a group here.
 */
@Component
@ConditionalOnProperty(name = "basketry.stock-requests.consumer.enabled", havingValue = "true")
class StockRequestListener {

    private final StockRequestParser parser;
    private final StockService stock;

    StockRequestListener(StockRequestParser parser, StockService stock) {
        this.parser = parser;
        this.stock = stock;
    }

    @KafkaListener(id = "reserve-requests", idIsGroup = false, topics = "${basketry.stock-requests.reserve-topic}")
    public void onReserveRequest(ConsumerRecord<String, String> record) {
        stock.reserve(parser.parseReserve(record.value()));
    }

    @KafkaListener(id = "release-requests", idIsGroup = false, topics = "${basketry.stock-requests.release-topic}")
    public void onReleaseRequest(ConsumerRecord<String, String> record) {
        stock.release(parser.parseRelease(record.value()));
    }
}
