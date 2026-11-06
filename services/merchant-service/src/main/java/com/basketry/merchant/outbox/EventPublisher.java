package com.basketry.merchant.outbox;

/** Sends one record and waits for it to be acknowledged. */
public interface EventPublisher {

    /** Returns only once the record is acknowledged; any exception means it may not have been sent. */
    void publish(String key, String payload);
}
