import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Only what the service uses so far (ROADMAP A3). Security and the internal key
// come with the pricing endpoint (A6), the resource server with the coupon
// endpoints (A7), and Kafka with the order.succeeded consumer (A8).
dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // The web server, for the health endpoint.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The coupon table (Hibernate only validates it against Flyway's schema).
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Flyway owns the schema (DESIGN.md Section 7); the starter brings JDBC and Hikari.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // In-memory H2 by default; PostgreSQL with the "postgres" profile (DESIGN.md Section 7).
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    // Flyway's PostgreSQL support is a separate module since Flyway 10.
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Only for postgresTest; the default test task never starts a container.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The default run: in-memory H2, no Docker. Tests tagged "postgres" run only in postgresTest.
tasks.test {
    useJUnitPlatform {
        excludeTags("postgres")
    }
}

// The whole suite again, on a real PostgreSQL in a Testcontainers container
// (support/PostgresContextCustomizerFactory). Needs Docker; not part of build or check.
tasks.register<Test>("postgresTest") {
    description = "Runs every test on PostgreSQL in a container. Needs Docker."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("basketry.test.postgres", "true")
    shouldRunAfter(tasks.test)
}
