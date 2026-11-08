// Root build: declares plugin versions once; each service applies what it needs.
// Stack per DESIGN.md Section 7: Java 25 (LTS), Spring Boot 4.1.
plugins {
    id("org.springframework.boot") version "4.1.1" apply false
}

subprojects {
    group = "com.basketry"
    version = "0.1.0-SNAPSHOT"
}

// Both services' tests on PostgreSQL (needs Docker): .\gradlew.bat postgresTest
tasks.register("postgresTest") {
    description = "Runs every service's tests on PostgreSQL in a container. Needs Docker."
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:postgresTest" })
}
