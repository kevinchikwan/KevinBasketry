package com.basketry.identity.merchantstatus;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes merchant.status.changed. The value arrives as a plain string and is
 * parsed by the handler, so a bad record reaches the error handler with its
 * original content. The offset is committed after this method returns, and
 * failures are decided in {@link MerchantStatusConsumerConfig}.
 */
@Component
@ConditionalOnProperty(name = "basketry.merchant-status.consumer.enabled", havingValue = "true")
class MerchantStatusListener {

    private final MerchantStatusHandler handler;

    MerchantStatusListener(MerchantStatusHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${basketry.merchant-status.topic}")
    public void onRecord(ConsumerRecord<String, String> record) {
        handler.handle(record.value());
    }
}
