package com.hyperscale.commerce.orderquery.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

public class CorrelationIdRecordInterceptor implements RecordInterceptor<String, String> {
  private static final Logger logger =
      LoggerFactory.getLogger(CorrelationIdRecordInterceptor.class);

  public static final String CORRELATION_ID_MDC_KEY = "correlationId";
  public static final String TRACE_ID_MDC_KEY = "traceId";
  public static final String SPAN_ID_MDC_KEY = "spanId";
  public static final String CORRELATION_ID_RECORD_HEADER = "correlation-id";

  private final ObservationRegistry observationRegistry;
  private final Tracer tracer;
  private final ThreadLocal<Observation> currentObservation = new ThreadLocal<>();
  private final ThreadLocal<Observation.Scope> currentScope = new ThreadLocal<>();
  private final ThreadLocal<Throwable> currentError = new ThreadLocal<>();

  public CorrelationIdRecordInterceptor(ObservationRegistry observationRegistry, Tracer tracer) {
    this.observationRegistry = observationRegistry;
    this.tracer = tracer;
  }

  @Override
  public ConsumerRecord<String, String> intercept(
      ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
    Header header = record.headers().lastHeader(CORRELATION_ID_RECORD_HEADER);
    String correlationId =
        header != null && header.value() != null
            ? new String(header.value(), StandardCharsets.UTF_8)
            : UUID.randomUUID().toString();

    KafkaRecordReceiverContext context =
        new KafkaRecordReceiverContext(record, "order-placed-consumer", () -> "kafka");
    Observation observation =
        Observation.start("kafka.receive", () -> context, observationRegistry);
    Observation.Scope scope = observation.openScope();
    currentObservation.set(observation);
    currentScope.set(scope);
    currentError.set(null);

    Span span = tracer.currentSpan();
    MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
    if (span != null) {
      TraceContext spanContext = span.context();
      MDC.put(TRACE_ID_MDC_KEY, spanContext.traceId());
      MDC.put(SPAN_ID_MDC_KEY, spanContext.spanId());
      logger.info(
          "Consumed record from {} traceId={} spanId={} correlationId={}",
          record.topic(),
          spanContext.traceId(),
          spanContext.spanId(),
          correlationId);
    } else {
      logger.info("Consumed record from {} correlationId={}", record.topic(), correlationId);
    }
    return record;
  }

  @Override
  public void success(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
    // lifecycle handled in afterRecord
  }

  @Override
  public void failure(
      ConsumerRecord<String, String> record,
      Exception exception,
      Consumer<String, String> consumer) {
    currentError.set(exception);
  }

  @Override
  public void afterRecord(
      ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
    Observation.Scope scope = currentScope.get();
    if (scope != null) {
      scope.close();
    }
    Observation observation = currentObservation.get();
    if (observation != null) {
      Throwable error = currentError.get();
      if (error != null) {
        observation.error(error);
      }
      observation.stop();
    }
    currentScope.remove();
    currentObservation.remove();
    currentError.remove();
    MDC.remove(CORRELATION_ID_MDC_KEY);
    MDC.remove(TRACE_ID_MDC_KEY);
    MDC.remove(SPAN_ID_MDC_KEY);
  }
}
