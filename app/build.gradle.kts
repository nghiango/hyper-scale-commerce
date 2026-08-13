plugins {
  kotlin("jvm") version "2.2.21"
  kotlin("plugin.spring") version "2.2.21"
  id("org.springframework.boot") version "4.0.0"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "7.0.2"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.hyperscale.commerce"

version = "0.0.1-SNAPSHOT"

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += sourceSets.main.get().output
    }

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
  implementation("net.logstash.logback:logstash-logback-encoder:8.0")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  runtimeOnly("org.postgresql:postgresql")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

  detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
  detekt("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

  "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
  "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
  "integrationTestImplementation"("org.testcontainers:junit-jupiter:1.21.3")
  "integrationTestImplementation"("org.testcontainers:postgresql:1.21.3")
}

tasks.register<Test>("integrationTest") {
  description = "Runs integration tests against isolated Testcontainers infrastructure."
  group = "verification"
  testClassesDirs = integrationTestSourceSet.output.classesDirs
  classpath = integrationTestSourceSet.runtimeClasspath
  shouldRunAfter(tasks.test)
  testLogging { events("passed", "failed", "skipped") }
}

tasks.check { dependsOn("integrationTest") }

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging { events("passed", "failed", "skipped") }
}

spotless {
  kotlin { ktfmt() }
  kotlinGradle { ktfmt() }
}
