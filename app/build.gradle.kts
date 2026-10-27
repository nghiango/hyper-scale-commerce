plugins {
  java
  id("org.springframework.boot") version "4.0.0"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "7.0.2"
  checkstyle
}

group = "com.hyperscale.commerce"

version = "0.0.1-SNAPSHOT"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

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
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.projectlombok:lombok")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("io.micrometer:micrometer-tracing")
  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  implementation("io.zipkin.reporter2:zipkin-reporter-brave")
  implementation("io.zipkin.reporter2:zipkin-sender-urlconnection")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
  implementation("net.logstash.logback:logstash-logback-encoder:8.0")
  implementation("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")

  testImplementation("org.springframework.boot:spring-boot-starter-test")

  "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
  "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
  "integrationTestImplementation"("org.testcontainers:junit-jupiter:1.21.3")
  "integrationTestImplementation"("org.testcontainers:postgresql:1.21.3")
}

checkstyle {
  toolVersion = "10.21.4"
  configFile = rootProject.file("config/checkstyle/checkstyle.xml")
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
  java {
    eclipse()
    leadingTabsToSpaces(2)
    trimTrailingWhitespace()
    endWithNewline()
  }
}
