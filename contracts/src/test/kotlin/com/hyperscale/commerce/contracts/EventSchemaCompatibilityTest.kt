package com.hyperscale.commerce.contracts

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventSchemaCompatibilityTest {

  private lateinit var objectMapper: ObjectMapper

  @BeforeEach
  fun setUp() {
    objectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  }

  @Test
  fun `OrderPlacedEvent safely deserializes payloads containing unknown future fields`() {
    val futureJson =
        """
        {
          "version": 2,
          "eventId": "${UUID.randomUUID()}",
          "orderId": 12345,
          "status": "PLACED",
          "createdAt": "2026-08-17T10:00:00Z",
          "items": [
            {"sku": "SKU-FUTURE-1", "quantity": 3, "discountPercent": 15.5}
          ],
          "correlationId": "corr-123",
          "traceId": "trace-456",
          "parentSpanId": "span-789",
          "sampled": true,
          "loyaltyTier": "PLATINUM",
          "billingCurrency": "USD",
          "shippingWarehouse": "WH-US-EAST"
        }
        """
            .trimIndent()

    val event = objectMapper.readValue(futureJson, OrderPlacedEvent::class.java)

    assertThat(event.orderId).isEqualTo(12345L)
    assertThat(event.status).isEqualTo("PLACED")
    assertThat(event.items).hasSize(1)
    assertThat(event.items[0].sku).isEqualTo("SKU-FUTURE-1")
    assertThat(event.items[0].quantity).isEqualTo(3)
    assertThat(event.correlationId).isEqualTo("corr-123")
  }

  @Test
  fun `OrderPlacedEvent safely deserializes legacy payloads without optional trace fields`() {
    val legacyJson =
        """
        {
          "version": 1,
          "eventId": "${UUID.randomUUID()}",
          "orderId": 99999,
          "status": "PLACED",
          "createdAt": "2026-08-17T10:00:00Z",
          "items": [
            {"sku": "SKU-LEGACY-1", "quantity": 1}
          ]
        }
        """
            .trimIndent()

    val event = objectMapper.readValue(legacyJson, OrderPlacedEvent::class.java)

    assertThat(event.orderId).isEqualTo(99999L)
    assertThat(event.status).isEqualTo("PLACED")
    assertThat(event.correlationId).isNull()
    assertThat(event.traceId).isNull()
  }

  @Test
  fun `InventoryReservationFailedEvent roundtrip serialization and unknown field resilience`() {
    val failedJsonWithUnknowns =
        """
        {
          "version": 1,
          "eventId": "${UUID.randomUUID()}",
          "orderId": 88888,
          "reason": "Insufficient stock in regional warehouse",
          "createdAt": "2026-08-17T10:00:00Z",
          "correlationId": "corr-failed-1",
          "warehouseLocation": "US-WEST",
          "retryScheduled": false
        }
        """
            .trimIndent()

    val event =
        objectMapper.readValue(failedJsonWithUnknowns, InventoryReservationFailedEvent::class.java)

    assertThat(event.orderId).isEqualTo(88888L)
    assertThat(event.reason).isEqualTo("Insufficient stock in regional warehouse")
    assertThat(event.version).isEqualTo(1)
  }

  @Test
  fun `OrderCancelledEvent roundtrip serialization and unknown field resilience`() {
    val original =
        OrderCancelledEvent(
            version = 1,
            eventId = UUID.randomUUID().toString(),
            orderId = 77777L,
            reason = "Customer cancelled",
            createdAt = Instant.now(),
            correlationId = "corr-cancelled",
            traceId = "trace-cancelled",
            parentSpanId = "span-cancelled",
            sampled = true,
        )

    val json = objectMapper.writeValueAsString(original)
    val deserialized = objectMapper.readValue(json, OrderCancelledEvent::class.java)

    assertThat(deserialized.orderId).isEqualTo(original.orderId)
    assertThat(deserialized.reason).isEqualTo(original.reason)
    assertThat(deserialized.eventId).isEqualTo(original.eventId)
    assertThat(deserialized.version).isGreaterThanOrEqualTo(1)
  }
}
