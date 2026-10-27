package com.hyperscale.commerce.config.observability;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;

public class KafkaCorrelationProducerInterceptor implements ProducerInterceptor<String, String> {

  public static final String CORRELATION_ID_MDC_KEY = "correlationId";
  public static final String CORRELATION_ID_RECORD_HEADER = "correlation-id";

  @Override
  public void configure(Map<String, ?> configs) {
    // no configuration required
  }

  @Override
  public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
    String correlationId =
        MDC.get(CORRELATION_ID_MDC_KEY) != null
            ? MDC.get(CORRELATION_ID_MDC_KEY)
            : UUID.randomUUID().toString();
    record
        .headers()
        .add(
            new RecordHeader(
                CORRELATION_ID_RECORD_HEADER, correlationId.getBytes(StandardCharsets.UTF_8)));
    return record;
  }

  @Override
  public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    // no action required
  }

  @Override
  public void onAcknowledgement(RecordMetadata metadata, Exception exception, Headers headers) {
    // no action required
  }

  @Override
  public void close() {
    // no resources to close
  }
}
