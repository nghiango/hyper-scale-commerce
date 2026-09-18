plugins {
  java
  id("com.diffplug.spotless")
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.25.3")
  testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
  testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}

tasks.test { useJUnitPlatform() }
