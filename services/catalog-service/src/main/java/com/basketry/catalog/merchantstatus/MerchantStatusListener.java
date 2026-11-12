package com.basketry.catalog.merchantstatus;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes merchant.status.changed. It only parses the value and calls
 * {@link MerchantStatusService#apply}; failures are decided in
 * {@link MerchantStatusConsumerConfig}. The value arrives as a plain string, so
 * a bad record reaches the error handler with its original bytes. The offset is
 * committed after this method returns.
 *
 * <p><b>Never {@code @Transactional}</b>, and the container has no transaction
 * manager: the MERGE commits on its own, and the listings are invalidated only
 * after that commit. {@link com.basketry.catalog.cache.ListingInvalidator}
 * refuses to run inside a transaction, so an outer one would roll every status
 * change back and dead-letter it. ConsumerTransactionBoundaryTests checks this.
 */
@Component
@ConditionalOnProperty(name = "basketry.merchant-status.consumer.enabled", havingValue = "true")
class MerchantStatusListener {

    private final MerchantStatusParser parser;
    private final MerchantStatusService statuses;

    MerchantStatusListener(MerchantStatusParser parser, MerchantStatusService statuses) {
        this.parser = parser;
        this.statuses = statuses;
    }

    @KafkaListener(topics = "${basketry.merchant-status.topic}")
    public void onRecord(ConsumerRecord<String, String> record) {
        MerchantStatusEvent event = parser.parse(record.value());
        statuses.apply(event.merchantId(), event.active(), event.version());
    }
}
