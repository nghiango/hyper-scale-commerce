package com.hyperscale.commerce.config.loadshedding

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val RETRY_AFTER_HEADER = "Retry-After"
private const val DEFAULT_RETRY_AFTER_SECONDS = "5"
private const val FILTER_ORDER_OFFSET = 10
private const val LOAD_SHED_RESPONSE_BODY =
    "{\"error\":\"Service under high load, non-critical traffic temporarily shed\"}"

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET)
@EnableConfigurationProperties(LoadSheddingProperties::class)
class AdaptiveLoadShedderFilter(
    private val properties: LoadSheddingProperties,
    private val meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {

  private val forcedOverload = AtomicBoolean(false)
  private val inFlightDegradable = AtomicInteger(0)

  fun setForcedOverload(overloaded: Boolean) {
    forcedOverload.set(overloaded)
  }

  fun isOverloaded(): Boolean {
    return forcedOverload.get() || inFlightDegradable.get() >= properties.maxInFlightDegradable
  }

  override fun doFilterInternal(
      request: HttpServletRequest,
      response: HttpServletResponse,
      filterChain: FilterChain
  ) {
    if (shouldShed(request)) {
      shedRequest(request, response)
      return
    }

    val isDegradable = isDegradableEndpoint(request)
    if (isDegradable) {
      inFlightDegradable.incrementAndGet()
    }
    try {
      filterChain.doFilter(request, response)
    } finally {
      if (isDegradable) {
        inFlightDegradable.decrementAndGet()
      }
    }
  }

  private fun shouldShed(request: HttpServletRequest): Boolean {
    return properties.enabled && isDegradableEndpoint(request) && isOverloaded()
  }

  private fun isDegradableEndpoint(request: HttpServletRequest): Boolean {
    val uri = request.requestURI
    val isProtected =
        uri.startsWith("/actuator") || (request.method == "POST" && uri.startsWith("/orders"))
    return !isProtected && uri.startsWith("/catalog")
  }

  private fun shedRequest(request: HttpServletRequest, response: HttpServletResponse) {
    meterRegistry
        .counter(
            "load_shedding_dropped_total",
            "endpoint",
            request.requestURI,
        )
        .increment()
    response.status = HTTP_TOO_MANY_REQUESTS
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.setHeader(RETRY_AFTER_HEADER, DEFAULT_RETRY_AFTER_SECONDS)
    response.writer.write(LOAD_SHED_RESPONSE_BODY)
  }
}
