package com.hyperscale.commerce.orderquery.resilience

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val POISON_ORDER_ID = 99L
private const val VALID_ORDER_ID = 100L
private const val POISON_PAYLOAD = """{"version":1,"orderId":$POISON_ORDER_ID}"""
private const val SKU = "PERF-SKU-00001"
private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val MIN_RETRY_WINDOW_MS = 2500L

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectionConsumerFailureTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jdbcTemplate: JdbcTemplate,
    @param:Value("\${app.outbox.topic}") private val topic: String,
) {
  private val httpClient = HttpClient.newHttpClient()

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
  fun `poison message lands in the DLQ after bounded retries and valid messages still project`() {
    val startedAt = System.nanoTime()
    kafkaTemplate
        .send(topic, POISON_ORDER_ID.toString(), POISON_PAYLOAD)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val dlqRecord = awaitDlqRecord(topic + "-dlq")
    assertThat(dlqRecord.value()).isEqualTo(POISON_PAYLOAD)
    assertThat(readModelCount(POISON_ORDER_ID)).isZero()

    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    assertThat(elapsedMs).isGreaterThanOrEqualTo(MIN_RETRY_WINDOW_MS)

    val validPayload =
        """{"version":1,"eventId":"p6-05-$VALID_ORDER_ID","orderId":$VALID_ORDER_ID,"status":"PLACED","createdAt":"2026-08-14T00:00:00Z","items":[{"sku":"$SKU","quantity":1}]}"""
    kafkaTemplate
        .send(topic, VALID_ORDER_ID.toString(), validPayload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    awaitReadModelCount(VALID_ORDER_ID, 1)
    assertThat(awaitOrder(VALID_ORDER_ID).statusCode()).isEqualTo(200)

    val metrics = get("/actuator/prometheus").body()
    assertThat(metrics).contains("events_dlq_total")
    assertThat(metrics).contains("topic=\"order-placed-dlq\"")
    assertThat(metrics)
        .contains(
            "events_consumed_total",
            "consumer=\"order-query\"",
            "event_type=\"OrderPlaced\"",
            "outcome=\"failed\"",
        )
    assertThat(metrics)
        .contains(
            "events_consumed_total",
            "consumer=\"order-query\"",
            "event_type=\"OrderPlaced\"",
            "outcome=\"processed\"",
        )
  }

  private fun awaitDlqRecord(dlqTopic: String): ConsumerRecord<String, String> {
    val props = HashMap<String, Any>()
    props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafka.bootstrapServers
    props[ConsumerConfig.GROUP_ID_CONFIG] = "projection-dlq-test-group"
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf(dlqTopic))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (System.nanoTime() < deadline) {
        val record = consumer.poll(Duration.ofMillis(POLL_MILLIS)).firstOrNull()
        if (record != null) {
          return record
        }
      }
      throw AssertionError("No record in DLQ topic $dlqTopic within $TIMEOUT_SECONDS seconds")
    }
  }

  private fun awaitReadModelCount(orderId: Long, expected: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (readModelCount(orderId) == expected) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(readModelCount(orderId)).isEqualTo(expected)
  }

  private fun readModelCount(orderId: Long): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
        Long::class.java,
        orderId,
    ) ?: 0L
  }

  private fun awaitOrder(orderId: Long): HttpResponse<String> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = get("/orders/$orderId")
      if (response.statusCode() == 200) {
        return response
      }
      Thread.sleep(POLL_MILLIS)
    }
    return get("/orders/$orderId")
  }

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
