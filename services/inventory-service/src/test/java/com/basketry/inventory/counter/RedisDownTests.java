package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.config.RedisClientConfig;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.support.InventoryServiceTestBase;
import io.lettuce.core.ClientOptions;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Row 4 of the Day 10 review, with the real Redis client and nothing listening
 * on its port: needs no Docker, so it runs in both runs. Reservations still
 * succeed through PostgreSQL, quickly.
 */
class RedisDownTests extends InventoryServiceTestBase {

    private LettuceConnectionFactory connections;

    @AfterEach
    void closeTheClient() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void reservationsSucceedThroughPostgresWhenRedisIsUnreachable() {
        givenStock(42, 5);
        StockService service = serviceOn(unreachableRedis(), Clock.systemUTC());

        long started = System.nanoTime();
        assertThat(service.reserve(reserve(7, 42, 2))).isEqualTo(new Reserved());
        assertThat(service.reserve(reserve(8, 42, 2))).isEqualTo(new Reserved());
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(quantityOf(42)).isEqualTo(1);
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    /** The client the application builds: same timeouts, and the options RedisClientConfig sets. */
    private RedisStockCounter unreachableRedis() {
        ClientOptions.Builder options = ClientOptions.builder();
        new RedisClientConfig().rejectCommandsWhileDisconnected().customize(options);
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(500))
                        .clientOptions(options.build())
                        .build());
        connections.afterPropertiesSet();
        connections.start();
        return new RedisStockCounter(new StringRedisTemplate(connections));
    }
}
