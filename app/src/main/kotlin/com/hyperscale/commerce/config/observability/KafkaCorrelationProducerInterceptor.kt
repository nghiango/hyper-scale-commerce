package com.hyperscale.commerce.config.observability

import java.util.UUID
import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.MDC

class KafkaCorrelationProducerInterceptor : ProducerInterceptor<String, String> {

  override fun configure(configs: MutableMap<String, *>) {
    // no configuration required
  }

  override fun onSend(record: ProducerRecord<String, String>): ProducerRecord<String, String> {
    val correlationId = MDC.get(CORRELATION_ID_MDC_KEY) ?: UUID.randomUUID().toString()
    record
        .headers()
        .add(RecordHeader(CORRELATION_ID_RECORD_HEADER, correlationId.toByteArray(Charsets.UTF_8)))
    return record
  }

  override fun onAcknowledgement(metadata: RecordMetadata, exception: Exception?) {
    // no action required
  }

  override fun onAcknowledgement(
      metadata: RecordMetadata,
      exception: Exception?,
      headers: Headers
  ) {
    // no action required
  }

  override fun close() {
    // no resources to close
  }

  companion object {
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    const val CORRELATION_ID_RECORD_HEADER = "correlation-id"
  }
}
