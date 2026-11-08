package com.basketry.payment.outbox;

/** Sends one stored result and returns once the broker has acknowledged it, or throws. */
public interface ResultPublisher {

    void publish(String topic, String key, String payload);
}
