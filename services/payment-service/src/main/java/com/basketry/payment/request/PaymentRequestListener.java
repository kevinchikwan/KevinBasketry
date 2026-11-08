package com.basketry.payment.request;

import com.basketry.payment.payment.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the two request topics. It only turns a record into a command and
 * calls {@link PaymentService}; failures are decided in
 * {@link PaymentRequestConsumerConfig}. The value arrives as a plain string, so
 * a bad record reaches the error handler with its original bytes.
 *
 * <p><b>Never {@code @Transactional}</b>, and the container has no transaction
 * manager: PaymentService starts its own transactions, and needs a new one
 * after a lost race, because on PostgreSQL a failed statement aborts its whole
 * transaction (DESIGN.md Section 2). TransactionBoundaryTests checks this.
 *
 * <p>Both listeners are in the one consumer group of the service,
 * {@code spring.kafka.consumer.group-id}: an id is not a group here.
 */
@Component
@ConditionalOnProperty(name = "basketry.payment-requests.consumer.enabled", havingValue = "true")
class PaymentRequestListener {

    private final PaymentRequestParser parser;
    private final PaymentService payments;

    PaymentRequestListener(PaymentRequestParser parser, PaymentService payments) {
        this.parser = parser;
        this.payments = payments;
    }

    @KafkaListener(id = "charge-requests", idIsGroup = false, topics = "${basketry.payment-requests.charge-topic}")
    public void onChargeRequest(ConsumerRecord<String, String> record) {
        payments.authorize(parser.parseCharge(record.value()));
    }

    @KafkaListener(id = "refund-requests", idIsGroup = false, topics = "${basketry.payment-requests.refund-topic}")
    public void onRefundRequest(ConsumerRecord<String, String> record) {
        payments.refund(parser.parseRefund(record.value()));
    }
}
