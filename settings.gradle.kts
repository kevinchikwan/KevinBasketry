rootProject.name = "basketry"

// Services live under services/<name>; keep the Gradle project path short (:identity-service).
include("identity-service")
project(":identity-service").projectDir = file("services/identity-service")
include("catalog-service")
project(":catalog-service").projectDir = file("services/catalog-service")
include("merchant-service")
project(":merchant-service").projectDir = file("services/merchant-service")
include("inventory-service")
project(":inventory-service").projectDir = file("services/inventory-service")
include("payment-service")
project(":payment-service").projectDir = file("services/payment-service")
include("order-service")
project(":order-service").projectDir = file("services/order-service")
