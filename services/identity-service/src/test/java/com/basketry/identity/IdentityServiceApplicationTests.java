package com.basketry.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starting the context runs the Flyway migrations against H2 and then
 * Hibernate's schema validation, so this fails if the entities and the
 * migrations drift apart.
 */
@SpringBootTest
class IdentityServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
