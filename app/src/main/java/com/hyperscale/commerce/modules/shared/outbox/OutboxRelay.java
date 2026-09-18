package com.hyperscale.commerce.modules.shared.outbox;

import com.hyperscale.commerce.config.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class OutboxRelay {
  private static final long PUBLISH_TIMEOUT_SECONDS = 10L;
  private static final int MIN_TRACE_ID_LENGTH = 16;

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String topic;
  private final int claimLimit;
  private final Tracer tracer;
  private final ObjectMapper objectMapper;
  private final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
  private final Counter eventsPublished;

  public OutboxRelay(
      OutboxRepository outboxRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      String topic,
      int claimLimit,
      MeterRegistry meterRegistry,
      Tracer tracer,
      ObjectMapper objectMapper) {
    this.outboxRepository = outboxRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
    this.claimLimit = claimLimit;
    this.tracer = tracer;
    this.objectMapper = objectMapper;
    this.eventsPublished = meterRegistry.counter("events_published_total", "topic", topic);
  }

  @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:100}")
  public void publishDueEvents() {
    boolean hasMore = true;
    while (hasMore) {
      List<OutboxEvent> dueEvents = outboxRepository.claimDue(claimLimit);
      if (dueEvents.isEmpty()) {
        hasMore = false;
      } else {
        boolean keepGoing = publishBatch(dueEvents);
        hasMore = keepGoing && dueEvents.size() >= claimLimit;
      }
    }
  }

  private boolean publishBatch(List<OutboxEvent> dueEvents) {
    List<Long> publishedIds = Collections.synchronizedList(new ArrayList<>(dueEvents.size()));
    boolean continueProcessing = true;
    List<CompletableFuture<?>> futures = new ArrayList<>(dueEvents.size());

    for (OutboxEvent event : dueEvents) {
      TraceContextCarrier eventContext = parseTraceContext(event.payload());
      Span span = startOutboxSpan(eventContext);
      Tracer.SpanInScope scope = tracer.withSpan(span);
      MDC.put(
          CorrelationIdFilter.CORRELATION_ID_MDC_KEY,
          eventContext.correlationId() != null
              ? eventContext.correlationId()
              : UUID.randomUUID().toString());
      MDC.put(CorrelationIdFilter.TRACE_ID_MDC_KEY, span.context().traceId());
      MDC.put(CorrelationIdFilter.SPAN_ID_MDC_KEY, span.context().spanId());
      String destinationTopic = resolveDestinationTopic(event.eventType());
      try {
        CompletableFuture<?> sendFuture =
            kafkaTemplate.send(destinationTopic, event.aggregateId(), event.payload());
        CompletableFuture<?> wrappedFuture =
            sendFuture.whenComplete(
                (result, exception) -> {
                  try {
                    if (exception == null) {
                      publishedIds.add(event.id());
                      eventsPublished.increment();
                    } else {
                      logger.warn(
                          "Failed to publish outbox event {} to topic {}",
                          event.id(),
                          destinationTopic,
                          exception);
                    }
                  } finally {
                    scope.close();
                    span.end();
                  }
                });
        futures.add(wrappedFuture);
      } catch (Exception exception) {
        scope.close();
        span.end();
        logger.warn(
            "Failed to initiate publish for outbox event {} to topic {}",
            event.id(),
            destinationTopic,
            exception);
      } finally {
        MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        MDC.remove(CorrelationIdFilter.TRACE_ID_MDC_KEY);
        MDC.remove(CorrelationIdFilter.SPAN_ID_MDC_KEY);
      }
    }

    if (!futures.isEmpty()) {
      try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        continueProcessing = false;
      } catch (Exception exception) {
        logger.warn("Outbox batch publication encountered timeout or error", exception);
      }
    }

    if (!publishedIds.isEmpty()) {
      outboxRepository.markPublished(publishedIds);
    }
    return continueProcessing;
  }

  private String resolveDestinationTopic(String eventType) {
    return "OrderCancelled".equals(eventType) ? "order-cancelled" : topic;
  }

  private TraceContextCarrier parseTraceContext(String payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      return new TraceContextCarrier(
          textOrNull(root.get("correlationId")),
          textOrNull(root.get("traceId")),
          textOrNull(root.get("parentSpanId")),
          boolOrNull(root.get("sampled")));
    } catch (Exception ex) {
      return new TraceContextCarrier();
    }
  }

  private Span startOutboxSpan(TraceContextCarrier carrier) {
    if (carrier.traceId() != null
        && carrier.parentSpanId() != null
        && carrier.traceId().length() >= MIN_TRACE_ID_LENGTH) {
      TraceContext parentContext =
          tracer
              .traceContextBuilder()
              .traceId(carrier.traceId())
              .spanId(carrier.parentSpanId())
              .sampled(carrier.sampled())
              .build();
      return tracer.spanBuilder().setParent(parentContext).name("outbox.publish").start();
    }
    return tracer.spanBuilder().setNoParent().name("outbox.publish").start();
  }

  private static String textOrNull(JsonNode node) {
    return node != null && !node.isNull() ? node.asString() : null;
  }

  private static Boolean boolOrNull(JsonNode node) {
    return node != null && !node.isNull() ? node.asBoolean() : null;
  }

  private record TraceContextCarrier(
      String correlationId, String traceId, String parentSpanId, Boolean sampled) {
    TraceContextCarrier() {
      this(null, null, null, true);
    }
  }
}
