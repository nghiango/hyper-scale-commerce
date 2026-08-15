package com.hyperscale.commerce.orderquery.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import java.util.UUID
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext

class CorrelationIdRecordInterceptor(
    private val observationRegistry: ObservationRegistry,
    private val tracer: Tracer,
) : RecordInterceptor<String, String> {

  private val logger = LoggerFactory.getLogger(CorrelationIdRecordInterceptor::class.java)
  private val currentObservation = ThreadLocal<Observation>()
  private val currentScope = ThreadLocal<Observation.Scope>()
  private val currentError = ThreadLocal<Throwable?>()

  override fun intercept(
      record: ConsumerRecord<String, String>,
      consumer: Consumer<String, String>
  ): ConsumerRecord<String, String> {
    val header = record.headers().lastHeader(CORRELATION_ID_RECORD_HEADER)
    val correlationId = header?.value()?.toString(Charsets.UTF_8) ?: UUID.randomUUID().toString()

    val context = KafkaRecordReceiverContext(record, "order-placed-consumer") { "kafka" }
    val observation = Observation.start("kafka.receive", { context }, observationRegistry)
    val scope = observation.openScope()
    currentObservation.set(observation)
    currentScope.set(scope)
    currentError.set(null)

    val span = tracer.currentSpan()
    MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
    if (span != null) {
      val spanContext = span.context()
      MDC.put(TRACE_ID_MDC_KEY, spanContext.traceId())
      MDC.put(SPAN_ID_MDC_KEY, spanContext.spanId())
      logger.info(
          "Consumed record from {} traceId={} spanId={} correlationId={}",
          record.topic(),
          spanContext.traceId(),
          spanContext.spanId(),
          correlationId,
      )
    } else {
      logger.info("Consumed record from {} correlationId={}", record.topic(), correlationId)
    }
    return record
  }

  override fun success(record: ConsumerRecord<String, String>, consumer: Consumer<String, String>) {
    // lifecycle handled in afterRecord
  }

  override fun failure(
      record: ConsumerRecord<String, String>,
      exception: Exception,
      consumer: Consumer<String, String>
  ) {
    currentError.set(exception)
  }

  override fun afterRecord(
      record: ConsumerRecord<String, String>,
      consumer: Consumer<String, String>
  ) {
    currentScope.get()?.close()
    currentObservation.get()?.also {
      currentError.get()?.let { error -> it.error(error) }
      it.stop()
    }
    currentScope.remove()
    currentObservation.remove()
    currentError.remove()
    MDC.remove(CORRELATION_ID_MDC_KEY)
    MDC.remove(TRACE_ID_MDC_KEY)
    MDC.remove(SPAN_ID_MDC_KEY)
  }

  companion object {
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    const val TRACE_ID_MDC_KEY = "traceId"
    const val SPAN_ID_MDC_KEY = "spanId"
    const val CORRELATION_ID_RECORD_HEADER = "correlation-id"
  }
}
