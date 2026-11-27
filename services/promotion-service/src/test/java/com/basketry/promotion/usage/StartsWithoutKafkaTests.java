package com.basketry.promotion.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

/**
 * promotion-service starts, answers and keeps its listener running with no
 * broker at all (the test properties point Kafka at 127.0.0.1:1): the coupon
 * endpoints and pricing never need Kafka, and the listener finds the broker
 * once it is there (services/README.md, the coupon steps by hand).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "basketry.coupon-usage.consumer.enabled=true")
class StartsWithoutKafkaTests extends PromotionServiceTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    /**
     * Mutations: {@code spring.kafka.listener.missing-topics-fatal: true} (the
     * container's topic check fails without a broker, and so does the start);
     * {@code spring.kafka.admin.auto-create: true} with fail-fast (startup
     * waits for the dead-letter topic's broker).
     */
    @Test
    void theServiceStartsAndAnswersWithNoBroker() throws Exception {
        MessageListenerContainer container = listeners.getListenerContainer("promotion-order-succeeded");
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isTrue();

        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
