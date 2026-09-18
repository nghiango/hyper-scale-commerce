package com.hyperscale.commerce.orderquery.observability;

import com.hyperscale.commerce.orderquery.config.AppProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String INSTANCE_ID_HEADER = "X-Instance-Id";
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";
  public static final String INSTANCE_ID_MDC_KEY = "instanceId";
  public static final String TRACE_ID_MDC_KEY = "traceId";
  public static final String SPAN_ID_MDC_KEY = "spanId";
  public static final String CORRELATION_ID_RECORD_HEADER = "correlation-id";

  private final Tracer tracer;
  private final String instanceId;

  public CorrelationIdFilter(Tracer tracer, AppProperties appProperties) {
    this.tracer = tracer;
    this.instanceId = appProperties.instanceId();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = request.getHeader(CORRELATION_ID_HEADER);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
    MDC.put(INSTANCE_ID_MDC_KEY, instanceId);
    Span span = tracer.currentSpan();
    if (span != null) {
      TraceContext context = span.context();
      MDC.put(TRACE_ID_MDC_KEY, context.traceId());
      MDC.put(SPAN_ID_MDC_KEY, context.spanId());
      response.setHeader(TRACE_ID_HEADER, context.traceId());
    }
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    response.setHeader(INSTANCE_ID_HEADER, instanceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(CORRELATION_ID_MDC_KEY);
      MDC.remove(INSTANCE_ID_MDC_KEY);
      MDC.remove(TRACE_ID_MDC_KEY);
      MDC.remove(SPAN_ID_MDC_KEY);
    }
  }
}
