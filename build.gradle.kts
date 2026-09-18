plugins {
  id("com.diffplug.spotless") version "7.0.2" apply false
}

subprojects {
  group = "com.hyperscale.commerce"
  version = "0.0.1-SNAPSHOT"

  repositories { mavenCentral() }

  plugins.withId("java") {
    configure<JavaPluginExtension> { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.release.set(21)
    }
  }

  plugins.withId("com.diffplug.spotless") {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      java {
        googleJavaFormat("1.24.0")
        targetExclude("**/build/**", "**/generated-sources/**")
      }
      kotlinGradle { ktfmt() }
    }
  }
}
