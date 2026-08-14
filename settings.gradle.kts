rootProject.name = "hyper-scale-commerce"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("app")
include("contracts")
include("order-query")
