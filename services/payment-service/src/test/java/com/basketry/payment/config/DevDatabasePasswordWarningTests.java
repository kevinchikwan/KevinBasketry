package com.basketry.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DevDatabasePasswordWarningTests {

    private static final String WARNING = "The database password is a local development default";

    @Test
    void theDevDefaultFromThePostgresProfileIsWarnedAbout(CapturedOutput output) {
        new DevDatabasePasswordWarning("local-dev-only-payment").warnIfDevDefault();

        assertThat(output).contains("WARN").contains(WARNING).doesNotContain("local-dev-only-payment");
    }

    @Test
    void aPasswordFromTheEnvironmentIsNotWarnedAbout(CapturedOutput output) {
        new DevDatabasePasswordWarning("a-real-password-from-a-secret").warnIfDevDefault();

        assertThat(output).doesNotContain(WARNING);
    }

    @Test
    void theEmptyH2PasswordIsNotWarnedAbout(CapturedOutput output) {
        new DevDatabasePasswordWarning("").warnIfDevDefault();

        assertThat(output).doesNotContain(WARNING);
    }
}
