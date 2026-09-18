package com.hyperscale.commerce.config.loadshedding;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + AdaptiveLoadShedderFilter.FILTER_ORDER_OFFSET)
@EnableConfigurationProperties(LoadSheddingProperties.class)
public class AdaptiveLoadShedderFilter extends OncePerRequestFilter {

  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final String RETRY_AFTER_HEADER = "Retry-After";
  private static final String DEFAULT_RETRY_AFTER_SECONDS = "5";
  static final int FILTER_ORDER_OFFSET = 10;
  private static final String LOAD_SHED_RESPONSE_BODY =
      "{\"error\":\"Service under high load, non-critical traffic temporarily shed\"}";

  private final LoadSheddingProperties properties;
  private final MeterRegistry meterRegistry;

  private final AtomicBoolean forcedOverload = new AtomicBoolean(false);
  private final AtomicInteger inFlightDegradable = new AtomicInteger(0);

  public AdaptiveLoadShedderFilter(LoadSheddingProperties properties, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  public void setForcedOverload(boolean overloaded) {
    forcedOverload.set(overloaded);
  }

  public boolean isOverloaded() {
    return forcedOverload.get() || inFlightDegradable.get() >= properties.maxInFlightDegradable();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (shouldShed(request)) {
      shedRequest(request, response);
      return;
    }

    boolean isDegradable = isDegradableEndpoint(request);
    if (isDegradable) {
      inFlightDegradable.incrementAndGet();
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (isDegradable) {
        inFlightDegradable.decrementAndGet();
      }
    }
  }

  private boolean shouldShed(HttpServletRequest request) {
    return properties.enabled() && isDegradableEndpoint(request) && isOverloaded();
  }

  private boolean isDegradableEndpoint(HttpServletRequest request) {
    String uri = request.getRequestURI();
    boolean isProtected =
        uri.startsWith("/actuator")
            || "POST".equals(request.getMethod())
            || uri.startsWith("/orders");
    return !isProtected && uri.startsWith("/catalog");
  }

  private void shedRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    meterRegistry
        .counter("load_shedding_dropped_total", "endpoint", request.getRequestURI())
        .increment();
    response.setStatus(HTTP_TOO_MANY_REQUESTS);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(RETRY_AFTER_HEADER, DEFAULT_RETRY_AFTER_SECONDS);
    response.getWriter().write(LOAD_SHED_RESPONSE_BODY);
  }
}
