package com.hyperscale.commerce.config.ratelimit

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val RETRY_AFTER_HEADER = "Retry-After"
private const val RATELIMIT_LIMIT_HEADER = "X-RateLimit-Limit"
private const val RATELIMIT_REMAINING_HEADER = "X-RateLimit-Remaining"
private const val FILTER_ORDER_OFFSET = 15
private const val CACHE_MAX_CLIENTS = 100_000L
private const val CACHE_WINDOW_SECONDS = 60L
private const val RATE_LIMIT_RESPONSE_BODY =
    "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please retry later.\"}"

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET)
@EnableConfigurationProperties(ClientRateLimitProperties::class)
class ClientRateLimitFilter(
    private val properties: ClientRateLimitProperties,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) : OncePerRequestFilter() {

  private val rateLimitedCounter: Counter? =
      meterRegistry?.counter("http_rate_limited_requests_total")

  private val requestCounts: Cache<String, AtomicInteger> =
      Caffeine.newBuilder()
          .maximumSize(CACHE_MAX_CLIENTS)
          .expireAfterWrite(Duration.ofSeconds(CACHE_WINDOW_SECONDS))
          .build()

  override fun doFilterInternal(
      request: HttpServletRequest,
      response: HttpServletResponse,
      filterChain: FilterChain,
  ) {
    if (!properties.enabled || isExcluded(request)) {
      filterChain.doFilter(request, response)
      return
    }

    val clientId = resolveClientId(request)
    val counter = requestCounts.get(clientId) { AtomicInteger(0) }
    val currentCount = counter.incrementAndGet()

    val remaining = (properties.limitPerMinute - currentCount).coerceAtLeast(0)
    response.setHeader(RATELIMIT_LIMIT_HEADER, properties.limitPerMinute.toString())
    response.setHeader(RATELIMIT_REMAINING_HEADER, remaining.toString())

    if (currentCount > properties.limitPerMinute) {
      rateLimitedCounter?.increment()
      response.status = HTTP_TOO_MANY_REQUESTS
      response.contentType = MediaType.APPLICATION_JSON_VALUE
      response.setHeader(RETRY_AFTER_HEADER, properties.retryAfterSeconds.toString())
      response.writer.write(RATE_LIMIT_RESPONSE_BODY)
      return
    }

    filterChain.doFilter(request, response)
  }

  fun reset() {
    requestCounts.invalidateAll()
  }

  private fun resolveClientId(request: HttpServletRequest): String {
    val apiKey = request.getHeader("X-API-Key")
    if (!apiKey.isNullOrBlank()) return "api-key:$apiKey"

    val forwarded = request.getHeader("X-Forwarded-For")
    if (!forwarded.isNullOrBlank()) return "ip:${forwarded.split(",")[0].trim()}"

    return "ip:${request.remoteAddr ?: "unknown"}"
  }

  private fun isExcluded(request: HttpServletRequest): Boolean {
    val uri = request.requestURI
    return uri.startsWith("/actuator")
  }
}
