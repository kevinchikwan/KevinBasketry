package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The port in application.yml, read from the file itself: Spring tests run on a
 * mock or random port, so nothing else would notice a port copied from another
 * service. 8086 is promotion-service's in the fixed map of services/README.md.
 */
class ApplicationFileTests {

    /** Mutation: 8085, order-service's port, left from a copy. */
    @Test
    void thePortIs8086() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();

        assertThat(application.getProperty("server.port")).isEqualTo("8086");
    }

    /**
     * Flyway owns the schema: Hibernate only validates it, and never holds a
     * session open across a web request. Mutations: {@code ddl-auto: update};
     * {@code open-in-view: true}.
     */
    @Test
    void hibernateOnlyValidatesAndOpenInViewIsOff() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();

        assertThat(application.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(application.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    /**
     * The web layer never logs request details (headers, so the internal key),
     * and Tomcat's access log stays off: neither shows in the leak test's log
     * capture (the access log goes to its own file). Mutations:
     * {@code log-request-details: true}; {@code accesslog.enabled: true}.
     */
    @Test
    void requestDetailsAndTheAccessLogAreOff() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();

        assertThat(application.getProperty("spring.mvc.log-request-details")).isEqualTo("false");
        assertThat(application.getProperty("server.tomcat.accesslog.enabled")).isEqualTo("false");
    }

    /**
     * The order.succeeded listener runs unless switched off, in group
     * promotion-service, reading bytes (a value that is not UTF-8 is refused,
     * not repaired) and committing after each record. Mutations:
     * {@code consumer.enabled: false}; group-id order-service; the
     * StringDeserializer for the value; ack-mode batch.
     */
    @Test
    void theUsageListenerIsOnByDefault() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();

        assertThat(application.getProperty("basketry.coupon-usage.consumer.enabled")).isEqualTo("true");
        assertThat(application.getProperty("spring.kafka.consumer.group-id")).isEqualTo("promotion-service");
        assertThat(application.getProperty("spring.kafka.consumer.key-deserializer"))
                .isEqualTo("org.apache.kafka.common.serialization.ByteArrayDeserializer");
        assertThat(application.getProperty("spring.kafka.consumer.value-deserializer"))
                .isEqualTo("org.apache.kafka.common.serialization.ByteArrayDeserializer");
        assertThat(application.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo("false");
        assertThat(application.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("record");
    }
}
