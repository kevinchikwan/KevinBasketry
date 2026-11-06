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

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // NimbusJwtEncoder/Decoder and JWK types for RS256 tokens and the JWKS endpoint.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // Bearer-token authentication for protected endpoints, using the JwtDecoder in JwtKeyConfig.
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Consumes merchant.status.changed, and publishes what cannot be applied to its dead-letter topic.
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Embedded KRaft broker, so no test needs Docker.
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
