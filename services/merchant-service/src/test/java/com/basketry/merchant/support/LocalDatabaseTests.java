package com.basketry.merchant.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The default in-memory H2 database of a local run outlives its pool's
 * connections (DESIGN.md Section 7, Local runs). Without DB_CLOSE_DELAY=-1, H2
 * drops an in-memory database when its last connection closes: a service left
 * idle long enough for Hikari to retire every connection (or a laptop's sleep)
 * then answered 500 with "Table not found".
 *
 * <p>The URL is read from application.yml itself, and the database is given a
 * name of its own: every Spring test context in this JVM holds connections to
 * the default database, which would keep it alive whatever the URL says.
 */
class LocalDatabaseTests {

    private static final String DATABASE = "merchant";

    /** Mutation: ;DB_CLOSE_DELAY=-1 removed from application.yml (the table is gone with the pool). */
    @Test
    void theDefaultDatabaseOutlivesEveryConnectionOfItsPool() throws SQLException {
        String configured = configuredUrl();
        assertThat(configured).startsWith("jdbc:h2:mem:" + DATABASE + ";");
        String url = configured.replaceFirst("^jdbc:h2:mem:" + DATABASE + ";",
                "jdbc:h2:mem:local-database-test-" + UUID.randomUUID() + ";");

        try (HikariDataSource pool = pool(url);
             Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE kept (id INT PRIMARY KEY)");
            statement.execute("INSERT INTO kept VALUES (7)");
        }
        // Every connection of that pool is closed now.

        try (HikariDataSource pool = pool(url);
             Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT id FROM kept")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(7);
            } finally {
                // Empties the test's database for the rest of this JVM (SHUTDOWN would
                // close this connection under the statement).
                statement.execute("DROP ALL OBJECTS");
            }
        }
    }

    private static String configuredUrl() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject().getProperty("spring.datasource.url");
    }

    private static HikariDataSource pool(String url) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername("sa");
        pool.setPassword("");
        pool.setMinimumIdle(1);
        pool.setMaximumPoolSize(2);
        return pool;
    }
}
