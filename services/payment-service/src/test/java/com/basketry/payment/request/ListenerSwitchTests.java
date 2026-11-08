package com.basketry.payment.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.payment.support.PaymentServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * With the switch off, as in every test that does not turn it on, there is no
 * listener and no error handler of ours: nothing tries to reach a broker.
 */
class ListenerSwitchTests extends PaymentServiceTestBase {

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private ApplicationContext context;

    @Test
    void theListenersAreOffWhenTheSwitchIsOff() {
        assertThat(listeners.getListenerContainers()).isEmpty();
        assertThat(context.getBeansOfType(PaymentRequestListener.class)).isEmpty();
        assertThat(context.getBeansOfType(DefaultErrorHandler.class)).isEmpty();
    }
}
