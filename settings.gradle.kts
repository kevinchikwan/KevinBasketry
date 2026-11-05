rootProject.name = "basketry"

// Services live under services/<name>; keep the Gradle project path short (:identity-service).
include("identity-service")
project(":identity-service").projectDir = file("services/identity-service")
include("merchant-service")
project(":merchant-service").projectDir = file("services/merchant-service")
