package com.basketry.inventory.config;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * While Redis is disconnected, a command fails at once instead of waiting in
 * Lettuce's queue. A queued command would otherwise run after its caller had
 * timed out and moved on, changing a counter nobody accounts for (DESIGN.md
 * Section 2, inventory-service). The client still reconnects in the background.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    /** Public so RedisDownTests can build the same client. */
    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
    }
}
