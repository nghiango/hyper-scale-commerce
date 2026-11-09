package com.hyperscale.commerce.config.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks the current server span as an error whenever the request results in a 5xx
 * response or an exception escapes the filter chain. Exceptions handled by
 * {@code @ControllerAdvice} produce error responses without ever marking the span
 * as failed, which hides real failures from {@code status=error} trace queries.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ErrorStatusTracingFilter extends OncePerRequestFilter {

  private static final int HTTP_SERVER_ERROR = 500;

  private final Tracer tracer;

  public ErrorStatusTracingFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } catch (RuntimeException | ServletException | IOException ex) {
      markError(ex);
      throw ex;
    }
    if (response.getStatus() >= HTTP_SERVER_ERROR) {
      markError("HTTP " + response.getStatus());
    }
  }

  private void markError(Throwable throwable) {
    Span span = tracer.currentSpan();
    if (span != null) {
      span.error(throwable);
    }
  }

  private void markError(String detail) {
    Span span = tracer.currentSpan();
    if (span != null) {
      span.tag("error", detail);
    }
  }
}
