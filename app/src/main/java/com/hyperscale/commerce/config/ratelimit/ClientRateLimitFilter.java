package com.hyperscale.commerce.config.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + ClientRateLimitFilter.FILTER_ORDER_OFFSET)
@EnableConfigurationProperties(ClientRateLimitProperties.class)
public class ClientRateLimitFilter extends OncePerRequestFilter {

  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final String RETRY_AFTER_HEADER = "Retry-After";
  private static final String RATELIMIT_LIMIT_HEADER = "X-RateLimit-Limit";
  private static final String RATELIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
  static final int FILTER_ORDER_OFFSET = 15;
  private static final long CACHE_MAX_CLIENTS = 100_000L;
  private static final long CACHE_WINDOW_SECONDS = 60L;
  private static final String RATE_LIMIT_RESPONSE_BODY =
      "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please retry later.\"}";

  private final ClientRateLimitProperties properties;
  private final Counter rateLimitedCounter;

  private final Cache<String, AtomicInteger> requestCounts =
      Caffeine.newBuilder()
          .maximumSize(CACHE_MAX_CLIENTS)
          .expireAfterWrite(Duration.ofSeconds(CACHE_WINDOW_SECONDS))
          .build();

  public ClientRateLimitFilter(
      ClientRateLimitProperties properties,
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    this.properties = properties;
    this.rateLimitedCounter =
        meterRegistry != null ? meterRegistry.counter("http_rate_limited_requests_total") : null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.enabled() || isExcluded(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String clientId = resolveClientId(request);
    AtomicInteger counter = requestCounts.get(clientId, k -> new AtomicInteger(0));
    int currentCount = counter.incrementAndGet();

    int remaining = Math.max(properties.limitPerMinute() - currentCount, 0);
    response.setHeader(RATELIMIT_LIMIT_HEADER, String.valueOf(properties.limitPerMinute()));
    response.setHeader(RATELIMIT_REMAINING_HEADER, String.valueOf(remaining));

    if (currentCount > properties.limitPerMinute()) {
      if (rateLimitedCounter != null) {
        rateLimitedCounter.increment();
      }
      response.setStatus(HTTP_TOO_MANY_REQUESTS);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setHeader(RETRY_AFTER_HEADER, String.valueOf(properties.retryAfterSeconds()));
      response.getWriter().write(RATE_LIMIT_RESPONSE_BODY);
      return;
    }

    filterChain.doFilter(request, response);
  }

  public void reset() {
    requestCounts.invalidateAll();
  }

  private String resolveClientId(HttpServletRequest request) {
    String apiKey = request.getHeader("X-API-Key");
    String forwarded = request.getHeader("X-Forwarded-For");
    if (apiKey != null && !apiKey.isBlank()) {
      return "api-key:" + apiKey;
    }
    if (forwarded != null && !forwarded.isBlank()) {
      return "ip:" + forwarded.split(",")[0].trim();
    }
    return "ip:" + (request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown");
  }

  private boolean isExcluded(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator");
  }
}
