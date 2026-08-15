package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.config.observability.CorrelationIdFilter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import tools.jackson.databind.ObjectMapper

@Suppress("LongParameterList")
class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val topic: String,
    private val claimLimit: Int,
    meterRegistry: MeterRegistry,
    private val tracer: Tracer,
    private val objectMapper: ObjectMapper,
) {
  private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)
  private val eventsPublished: Counter =
      meterRegistry.counter("events_published_total", "topic", topic)

  @Scheduled(fixedDelayString = "\${app.outbox.relay-interval-ms:100}")
  fun publishDueEvents() {
    var hasMore = true
    while (hasMore) {
      val dueEvents = outboxRepository.claimDue(claimLimit)
      if (dueEvents.isEmpty()) {
        hasMore = false
      } else {
        val keepGoing = publishBatch(dueEvents)
        hasMore = keepGoing && (dueEvents.size >= claimLimit)
      }
    }
  }

  private fun publishBatch(dueEvents: List<OutboxEvent>): Boolean {
    val publishedIds = ArrayList<Long>(dueEvents.size)
    var continueProcessing = true
    for (event in dueEvents) {
      val eventContext = parseTraceContext(event.payload)
      val span = startOutboxSpan(eventContext)
      val scope = tracer.withSpan(span)
      MDC.put(
          CorrelationIdFilter.CORRELATION_ID_MDC_KEY,
          eventContext.correlationId ?: UUID.randomUUID().toString())
      MDC.put(CorrelationIdFilter.TRACE_ID_MDC_KEY, span.context().traceId())
      MDC.put(CorrelationIdFilter.SPAN_ID_MDC_KEY, span.context().spanId())
      try {
        kafkaTemplate
            .send(topic, event.aggregateId, event.payload)
            .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        publishedIds.add(event.id)
        eventsPublished.increment()
      } catch (exception: KafkaException) {
        logger.warn("Failed to publish outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: ExecutionException) {
        logger.warn("Failed to publish outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: TimeoutException) {
        logger.warn("Timed out publishing outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        continueProcessing = false
        break
      } finally {
        MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)
        MDC.remove(CorrelationIdFilter.TRACE_ID_MDC_KEY)
        MDC.remove(CorrelationIdFilter.SPAN_ID_MDC_KEY)
        scope.close()
        span.end()
      }
    }
    if (publishedIds.isNotEmpty()) {
      outboxRepository.markPublished(publishedIds)
    }
    return continueProcessing
  }

  private fun parseTraceContext(payload: String): TraceContextCarrier {
    return runCatching {
          val root = objectMapper.readTree(payload)
          TraceContextCarrier(
              correlationId = root.get("correlationId")?.takeIf { !it.isNull }?.asText(),
              traceId = root.get("traceId")?.takeIf { !it.isNull }?.asText(),
              parentSpanId = root.get("parentSpanId")?.takeIf { !it.isNull }?.asText(),
              sampled = root.get("sampled")?.takeIf { !it.isNull }?.asBoolean(),
          )
        }
        .getOrNull() ?: TraceContextCarrier()
  }

  private fun startOutboxSpan(carrier: TraceContextCarrier): io.micrometer.tracing.Span {
    return if (carrier.traceId != null &&
        carrier.parentSpanId != null &&
        carrier.traceId.length >= MIN_TRACE_ID_LENGTH) {
      val parentContext =
          tracer
              .traceContextBuilder()
              .traceId(carrier.traceId)
              .spanId(carrier.parentSpanId)
              .sampled(carrier.sampled)
              .build()
      tracer.spanBuilder().setParent(parentContext).name("outbox.publish").start()
    } else {
      tracer.spanBuilder().setNoParent().name("outbox.publish").start()
    }
  }

  private data class TraceContextCarrier(
      val correlationId: String? = null,
      val traceId: String? = null,
      val parentSpanId: String? = null,
      val sampled: Boolean? = true,
  )

  private companion object {
    const val PUBLISH_TIMEOUT_SECONDS = 10L
    const val MIN_TRACE_ID_LENGTH = 16
  }
}
