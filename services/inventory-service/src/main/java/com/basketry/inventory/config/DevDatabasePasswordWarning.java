package com.basketry.inventory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs a WARN at startup when the database password is a local development
 * default from application-postgres.yml, so a deployment that forgot
 * BASKETRY_DB_PASSWORD does not go unnoticed (DESIGN.md Section 7). The
 * password itself is never logged.
 */
@Component
public class DevDatabasePasswordWarning {

    /** Every committed default starts with this, in application-postgres.yml and docker-compose.yml. */
    static final String DEV_DEFAULT_PREFIX = "local-dev-only-";

    private static final Logger log = LoggerFactory.getLogger(DevDatabasePasswordWarning.class);

    private final String password;

    public DevDatabasePasswordWarning(@Value("${spring.datasource.password:}") String password) {
        this.password = password;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void warnIfDevDefault() {
        if (usesDevDefault()) {
            log.warn("The database password is a local development default. Set BASKETRY_DB_PASSWORD;"
                    + " never deploy like this.");
        }
    }

    boolean usesDevDefault() {
        return password.startsWith(DEV_DEFAULT_PREFIX);
    }
}
