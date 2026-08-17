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
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(21)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
      compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
            "-Xannotation-default-target=param-property",
        )
      }
    }

    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
      compilerOptions {
        allWarningsAsErrors.set(true)
      }
    }
  }

  plugins.withId("io.gitlab.arturbosch.detekt") {
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
      buildUponDefaultConfig = true
      config.setFrom(rootProject.files("config/detekt/detekt.yml"))
      autoCorrect = false
      parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
      exclude("**/build/**")
      exclude("**/generated-sources/**")
      reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
      }
    }
  }

  plugins.withId("com.diffplug.spotless") {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      kotlin {
        ktfmt()
        targetExclude("**/build/**", "**/generated-sources/**")
      }
      kotlinGradle {
        ktfmt()
      }
    }
  }
}
