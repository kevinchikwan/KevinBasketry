package com.basketry.catalog.merchantstatus;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * With the switch off, as in every test that does not turn it on, there is no
 * listener, no error handler and no topic of ours: nothing tries to reach a
 * broker. Mutation: drop @ConditionalOnProperty from MerchantStatusListener.
 */
class ListenerSwitchTests extends CatalogServiceTestBase {

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private ApplicationContext context;

    @Test
    void theListenerIsOffWhenTheSwitchIsOff() {
        assertThat(listeners.getListenerContainers()).isEmpty();
        assertThat(context.getBeansOfType(MerchantStatusListener.class)).isEmpty();
        assertThat(context.getBeansOfType(DefaultErrorHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
    }
}
