plugins {
  kotlin("jvm") version "2.2.21" apply false
  kotlin("plugin.spring") version "2.2.21" apply false
  id("com.diffplug.spotless") version "7.0.2" apply false
  id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
  group = "com.hyperscale.commerce"
  version = "0.0.1-SNAPSHOT"

  repositories { mavenCentral() }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
  }

  plugins.withId("com.diffplug.spotless") {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      kotlin { ktfmt() }
      kotlinGradle { ktfmt() }
    }
  }
}
