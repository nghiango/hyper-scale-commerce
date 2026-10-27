package com.hyperscale.commerce.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventSchemaCompatibilityTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Test
  @DisplayName("OrderPlacedEvent safely deserializes payloads containing unknown future fields")
  void orderPlacedEventSafelyDeserializesPayloadsContainingUnknownFutureFields() throws Exception {
    String futureJson =
        """
        {
          "version": 2,
          "eventId": "%s",
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
            .formatted(UUID.randomUUID());

    OrderPlacedEvent event = objectMapper.readValue(futureJson, OrderPlacedEvent.class);

    assertThat(event.orderId()).isEqualTo(12345L);
    assertThat(event.status()).isEqualTo("PLACED");
    assertThat(event.items()).hasSize(1);
    assertThat(event.items().get(0).sku()).isEqualTo("SKU-FUTURE-1");
    assertThat(event.items().get(0).quantity()).isEqualTo(3);
    assertThat(event.correlationId()).isEqualTo("corr-123");
  }

  @Test
  @DisplayName("OrderPlacedEvent safely deserializes legacy payloads without optional trace fields")
  void orderPlacedEventSafelyDeserializesLegacyPayloadsWithoutOptionalTraceFields()
      throws Exception {
    String legacyJson =
        """
        {
          "version": 1,
          "eventId": "%s",
          "orderId": 99999,
          "status": "PLACED",
          "createdAt": "2026-08-17T10:00:00Z",
          "items": [
            {"sku": "SKU-LEGACY-1", "quantity": 1}
          ]
        }
        """
            .formatted(UUID.randomUUID());

    OrderPlacedEvent event = objectMapper.readValue(legacyJson, OrderPlacedEvent.class);

    assertThat(event.orderId()).isEqualTo(99999L);
    assertThat(event.status()).isEqualTo("PLACED");
    assertThat(event.correlationId()).isNull();
    assertThat(event.traceId()).isNull();
  }

  @Test
  @DisplayName(
      "InventoryReservationFailedEvent roundtrip serialization and unknown field resilience")
  void inventoryReservationFailedEventRoundtripSerializationAndUnknownFieldResilience()
      throws Exception {
    String failedJsonWithUnknowns =
        """
        {
          "version": 1,
          "eventId": "%s",
          "orderId": 88888,
          "reason": "Insufficient stock in regional warehouse",
          "createdAt": "2026-08-17T10:00:00Z",
          "correlationId": "corr-failed-1",
          "warehouseLocation": "US-WEST",
          "retryScheduled": false
        }
        """
            .formatted(UUID.randomUUID());

    InventoryReservationFailedEvent event =
        objectMapper.readValue(failedJsonWithUnknowns, InventoryReservationFailedEvent.class);

    assertThat(event.orderId()).isEqualTo(88888L);
    assertThat(event.reason()).isEqualTo("Insufficient stock in regional warehouse");
    assertThat(event.version()).isEqualTo(1);
  }

  @Test
  @DisplayName("OrderCancelledEvent roundtrip serialization and unknown field resilience")
  void orderCancelledEventRoundtripSerializationAndUnknownFieldResilience() throws Exception {
    OrderCancelledEvent original =
        new OrderCancelledEvent(
            1,
            UUID.randomUUID().toString(),
            77777L,
            "Customer cancelled",
            Instant.now(),
            2L,
            "corr-cancelled",
            "trace-cancelled",
            "span-cancelled",
            true);

    String json = objectMapper.writeValueAsString(original);
    OrderCancelledEvent deserialized = objectMapper.readValue(json, OrderCancelledEvent.class);

    assertThat(deserialized.orderId()).isEqualTo(original.orderId());
    assertThat(deserialized.reason()).isEqualTo(original.reason());
    assertThat(deserialized.eventId()).isEqualTo(original.eventId());
    assertThat(deserialized.version()).isGreaterThanOrEqualTo(1);
  }
}
