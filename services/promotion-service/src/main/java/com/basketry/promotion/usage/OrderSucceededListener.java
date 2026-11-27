package com.basketry.promotion.usage;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The usage count's listener (DESIGN.md Section 5, promotion-service). It only
 * parses the record and hands it to {@link CouponUsageService}, and never runs
 * inside a transaction: the service opens its own. A record that can never be
 * counted goes to the dead-letter topic; a database failure is retried
 * (CouponUsageConsumerConfig). The id names the container only: the consumer
 * group is promotion-service (spring.kafka.consumer.group-id), as
 * order-service's listeners share theirs. On by default; tests switch it off.
 */
@Component
@ConditionalOnProperty(name = "basketry.coupon-usage.consumer.enabled", havingValue = "true")
class OrderSucceededListener {

    static final String ID = "promotion-order-succeeded";

    private final OrderSucceededParser parser;
    private final CouponUsageService usages;

    OrderSucceededListener(OrderSucceededParser parser, CouponUsageService usages) {
        this.parser = parser;
        this.usages = usages;
    }

    /** Bytes in, key and value (application.yml): the parser decodes the value strictly. */
    @KafkaListener(id = ID, idIsGroup = false, topics = OrderSucceededTopic.NAME)
    void onOrderSucceeded(ConsumerRecord<byte[], byte[]> record) {
        usages.record(parser.parse(record.value()));
    }
}
