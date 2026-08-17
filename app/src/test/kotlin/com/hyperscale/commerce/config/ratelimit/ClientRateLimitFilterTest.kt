package com.hyperscale.commerce.config.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ClientRateLimitFilterTest {

  private lateinit var filter: ClientRateLimitFilter
  private lateinit var filterChain: FilterChain
  private val meterRegistry = SimpleMeterRegistry()

  @BeforeEach
  fun setup() {
    val properties =
        ClientRateLimitProperties(enabled = true, limitPerMinute = 5, retryAfterSeconds = 60)
    filter = ClientRateLimitFilter(properties, meterRegistry)
    filterChain = mock(FilterChain::class.java)
  }

  @Test
  fun `allows requests within limit and sets ratelimit headers`() {
    val request = MockHttpServletRequest("GET", "/catalog/products/1")
    request.remoteAddr = "192.168.1.10"
    val response = MockHttpServletResponse()

    filter.doFilter(request, response, filterChain)

    assertThat(response.status).isEqualTo(200)
    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("5")
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("4")
    verify(filterChain, times(1)).doFilter(request, response)
  }

  @Test
  fun `blocks requests exceeding limit with HTTP 429 and Retry-After header`() {
    for (i in 1..5) {
      val request = MockHttpServletRequest("GET", "/catalog/products/1")
      request.remoteAddr = "10.0.0.1"
      val response = MockHttpServletResponse()
      filter.doFilter(request, response, filterChain)
      assertThat(response.status).isEqualTo(200)
    }

    // 6th request from same IP should be blocked
    val blockedRequest = MockHttpServletRequest("GET", "/catalog/products/1")
    blockedRequest.remoteAddr = "10.0.0.1"
    val blockedResponse = MockHttpServletResponse()

    filter.doFilter(blockedRequest, blockedResponse, filterChain)

    assertThat(blockedResponse.status).isEqualTo(429)
    assertThat(blockedResponse.getHeader("Retry-After")).isEqualTo("60")
    assertThat(blockedResponse.getHeader("X-RateLimit-Remaining")).isEqualTo("0")
    assertThat(blockedResponse.contentAsString).contains("Too Many Requests")
  }

  @Test
  fun `separate clients have independent rate limit quotas`() {
    for (i in 1..5) {
      val request = MockHttpServletRequest("GET", "/catalog/products/1")
      request.remoteAddr = "10.0.0.1"
      val response = MockHttpServletResponse()
      filter.doFilter(request, response, filterChain)
    }

    // Client 2 should NOT be blocked
    val client2Request = MockHttpServletRequest("GET", "/catalog/products/1")
    client2Request.remoteAddr = "10.0.0.2"
    val client2Response = MockHttpServletResponse()

    filter.doFilter(client2Request, client2Response, filterChain)
    assertThat(client2Response.status).isEqualTo(200)
  }

  @Test
  fun `actuator endpoints are excluded from rate limiting`() {
    for (i in 1..10) {
      val request = MockHttpServletRequest("GET", "/actuator/health")
      request.remoteAddr = "10.0.0.1"
      val response = MockHttpServletResponse()
      filter.doFilter(request, response, filterChain)
      assertThat(response.status).isEqualTo(200)
    }
  }
}
