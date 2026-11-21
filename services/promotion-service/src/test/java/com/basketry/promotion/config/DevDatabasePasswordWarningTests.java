package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The WARN when the postgres profile runs on its committed local default (DESIGN.md Section 7). */
@ExtendWith(OutputCaptureExtension.class)
class DevDatabasePasswordWarningTests {

    /** Mutations: the check inverted; the password itself in the WARN. */
    @Test
    void onlyTheLocalDefaultWarnsAndThePasswordIsNeverShown(CapturedOutput output) {
        new DevDatabasePasswordWarning("a-real-secret").warnIfDevDefault();
        assertThat(output).doesNotContain("WARN");

        new DevDatabasePasswordWarning("local-dev-only-promotion").warnIfDevDefault();
        assertThat(output).contains("WARN").contains("Set BASKETRY_DB_PASSWORD").doesNotContain("local-dev-only-promotion");
    }
}
