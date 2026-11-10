package com.basketry.catalog.config;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * While Redis is disconnected, a command fails at once instead of waiting in
 * Lettuce's queue (copied from inventory-service). A listing then goes to the
 * database straight away, and a queued write of a page can never run after its
 * caller has moved on. The client still reconnects in the background.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
    }
}
