package com.hyperscale.commerce.messaging

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SMOKE_TOPIC = "smoke-topic"
private const val SMOKE_KEY = "smoke-key"
private const val SMOKE_VALUE = "smoke-value"
private const val SMOKE_GROUP = "smoke-group"
private const val TIMEOUT_SECONDS = 10L
private const val POLL_MILLIS = 500L
private const val HEALTH_RETRIES = 20
private const val HEALTH_RETRY_DELAY_MILLIS = 500L

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaSmokeTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) {

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
  }

  @Test
  fun `produces and consumes a message via Kafka`() {
    kafkaTemplate.send(SMOKE_TOPIC, SMOKE_KEY, SMOKE_VALUE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val props = Properties()
    props["bootstrap.servers"] = bootstrapServers
    props["group.id"] = SMOKE_GROUP
    props["key.deserializer"] = StringDeserializer::class.java.name
    props["value.deserializer"] = StringDeserializer::class.java.name
    props["auto.offset.reset"] = "earliest"

    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf(SMOKE_TOPIC))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      var received: String? = null
      while (received == null && System.nanoTime() < deadline) {
        val records = consumer.poll(Duration.ofMillis(POLL_MILLIS))
        received = records.firstOrNull { it.key() == SMOKE_KEY }?.value()
      }
      assertThat(received).isEqualTo(SMOKE_VALUE)
    }
  }

  @Test
  fun `exposes kafka health component`() {
    val httpClient = HttpClient.newHttpClient()
    var health: String = ""
    repeat(HEALTH_RETRIES) {
      val response =
          httpClient.send(
              HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build(),
              HttpResponse.BodyHandlers.ofString())
      health = response.body()
      if (health.contains("\"kafka\":{\"status\":\"UP\"}")) {
        return@repeat
      }
      Thread.sleep(HEALTH_RETRY_DELAY_MILLIS)
    }
    assertThat(health).contains("\"kafka\":{\"status\":\"UP\"}")
  }
}
