plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  id("org.springframework.boot") version "4.0.0"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless")
  id("io.gitlab.arturbosch.detekt")
  id("org.jooq.jooq-codegen-gradle") version "3.19.28"
}

jooq {
  configuration {
    generator {
      database {
        name = "org.jooq.meta.extensions.ddl.DDLDatabase"
        properties {
          property {
            key = "scripts"
            value = "src/main/resources/db/migration-order-query"
          }
          property {
            key = "sort"
            value = "flyway"
          }
          property {
            key = "parseIgnoreErrors"
            value = "true"
          }
          property {
            key = "defaultNameCase"
            value = "lower"
          }
        }
      }
      target {
        packageName = "com.hyperscale.commerce.orderquery.jooq"
        directory = "build/generated-sources/jooq"
      }
    }
  }
}

sourceSets.main { java.srcDir("build/generated-sources/jooq") }

tasks.named("compileJava") { dependsOn("jooqCodegen") }

tasks.named("compileKotlin") { dependsOn("jooqCodegen") }

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += sourceSets.main.get().output
    }

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
  implementation(project(":contracts"))
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jackson")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("io.micrometer:micrometer-tracing")
  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
  implementation("io.micrometer:context-propagation")
  implementation("org.springframework.kafka:spring-kafka")
  implementation("net.logstash.logback:logstash-logback-encoder:8.0")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  runtimeOnly("org.postgresql:postgresql")

  jooqCodegen("org.jooq:jooq-meta-extensions:3.19.28")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

  detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
  detekt("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

  "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
  "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
  "integrationTestImplementation"("org.testcontainers:junit-jupiter:1.21.3")
  "integrationTestImplementation"("org.testcontainers:postgresql:1.21.3")
  "integrationTestImplementation"("org.testcontainers:kafka:1.21.3")
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

tasks.named<JavaExec>("bootRun") { jvmArgs("-Xms256m", "-Xmx512m", "-XX:+UseG1GC") }

tasks.withType<Test> {
  useJUnitPlatform()
  maxHeapSize = "1g"
  jvmArgs("-XX:+UseG1GC")
  systemProperty("spring.config.name", "orderquery")
  testLogging { events("passed", "failed", "skipped") }
}
