package com.hyperscale.commerce.config.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ErrorStatusTracingFilterTest {

  private ErrorStatusTracingFilter filter;
  private Tracer tracer;
  private Span span;

  @BeforeEach
  void setup() {
    tracer = mock(Tracer.class);
    span = mock(Span.class);
    when(tracer.currentSpan()).thenReturn(span);
    filter = new ErrorStatusTracingFilter(tracer);
  }

  private void runWithStatus(int status) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(status);
    filter.doFilter(request, response, chain);
  }

  @Test
  @DisplayName("marks span error when handler returns 500 without throwing")
  void marksSpanErrorOnHandled500() throws Exception {
    runWithStatus(500);
    verify(span).tag("error", "HTTP 500");
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 400, 404, 409, 422, 429})
  @DisplayName("does not mark span error for success and client-error responses")
  void doesNotMarkErrorForNonServerErrors(int status) throws Exception {
    runWithStatus(status);
    verify(span, never()).tag(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    verify(span, never()).error(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("marks span error and rethrows when the chain throws")
  void marksSpanErrorWhenChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    RuntimeException failure = new RuntimeException("boom");
    FilterChain chain =
        (req, res) -> {
          throw failure;
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
    verify(span).error(failure);
  }

  @Test
  @DisplayName("does nothing when there is no current span")
  void toleratesMissingSpan() throws Exception {
    when(tracer.currentSpan()).thenReturn(null);
    runWithStatus(500);
    // no exception, no interaction with a span
    verify(tracer).currentSpan();
  }
}
