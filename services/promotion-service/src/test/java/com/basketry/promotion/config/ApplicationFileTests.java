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
}
