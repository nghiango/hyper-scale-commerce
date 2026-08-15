package com.hyperscale.commerce.orderquery.observability

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CorrelationIdFilter(private val tracer: Tracer) : OncePerRequestFilter() {

  override fun doFilterInternal(
      request: HttpServletRequest,
      response: HttpServletResponse,
      filterChain: FilterChain
  ) {
    val correlationId = request.getHeader(CORRELATION_ID_HEADER) ?: UUID.randomUUID().toString()
    MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
    val span = tracer.currentSpan()
    if (span != null) {
      val context = span.context()
      MDC.put(TRACE_ID_MDC_KEY, context.traceId())
      MDC.put(SPAN_ID_MDC_KEY, context.spanId())
      response.setHeader(TRACE_ID_HEADER, context.traceId())
    }
    response.setHeader(CORRELATION_ID_HEADER, correlationId)
    try {
      filterChain.doFilter(request, response)
    } finally {
      MDC.remove(CORRELATION_ID_MDC_KEY)
      MDC.remove(TRACE_ID_MDC_KEY)
      MDC.remove(SPAN_ID_MDC_KEY)
    }
  }

  companion object {
    const val CORRELATION_ID_HEADER = "X-Correlation-Id"
    const val TRACE_ID_HEADER = "X-Trace-Id"
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    const val TRACE_ID_MDC_KEY = "traceId"
    const val SPAN_ID_MDC_KEY = "spanId"
    const val CORRELATION_ID_RECORD_HEADER = "correlation-id"
  }
}
