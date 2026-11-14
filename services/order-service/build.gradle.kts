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

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // The web server, and RestClient for identity-service, catalog-service and merchant-service.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Bearer-token authentication: verifies identity-service's RS256 tokens against its JWKS endpoint.
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // The outbox relay (DESIGN.md Section 5); the result listeners come with the coordinator.
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // In-memory H2 by default; PostgreSQL with the "postgres" profile (DESIGN.md Section 7).
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    // Flyway's PostgreSQL support is a separate module since Flyway 10.
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Embedded KRaft broker, so no test needs Docker.
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    // Only for postgresTest; the default test task never starts a container.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The default run: in-memory H2, an embedded broker and fake downstream services
// over real HTTP, no Docker. Tests tagged "postgres" run only in postgresTest.
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
