package com.basketry.order.outbox;

/** Sends one stored request or order event and returns once the broker has acknowledged it, or throws. */
public interface RequestPublisher {

    void publish(String topic, String key, String payload);
}
