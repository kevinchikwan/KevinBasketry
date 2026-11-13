package com.basketry.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The database application-postgres.yml points at, read from the file itself:
 * every Spring test replaces the profile's URL and user with the test
 * container's, so nothing else would notice a wrong name here. The database
 * and user are "orders" (DESIGN.md Section 7), as the Compose init script
 * creates them.
 */
class PostgresProfileFileTests {

    /** Mutations: the URL's database "postgres"; the default user "order". */
    @Test
    void theProfilePointsAtTheOrdersDatabaseAsTheOrdersUser() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-postgres.yml"));
        Properties profile = yaml.getObject();

        assertThat(profile.getProperty("spring.datasource.url"))
                .endsWith(":${BASKETRY_POSTGRES_PORT:5432}/orders");
        assertThat(profile.getProperty("spring.datasource.username")).isEqualTo("${BASKETRY_DB_USERNAME:orders}");
        assertThat(profile.getProperty("spring.datasource.password"))
                .isEqualTo("${BASKETRY_DB_PASSWORD:local-dev-only-orders}");
    }
}
