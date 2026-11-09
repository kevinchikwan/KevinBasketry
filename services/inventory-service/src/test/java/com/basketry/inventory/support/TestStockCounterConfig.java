package com.basketry.inventory.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In the default run, the in-memory fake replaces Redis for the whole
 * application. In postgresTest ({@value RedisContextCustomizerFactory#ENABLED})
 * there is no fake, and the real Redis counter serves every test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStockCounterConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = RedisContextCustomizerFactory.ENABLED, havingValue = "false", matchIfMissing = true)
    InMemoryStockCounter inMemoryStockCounter() {
        return new InMemoryStockCounter();
    }
}
