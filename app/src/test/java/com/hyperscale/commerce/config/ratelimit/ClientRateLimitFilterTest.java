package com.hyperscale.commerce.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientRateLimitFilterTest {

  private ClientRateLimitFilter filter;
  private FilterChain filterChain;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void setup() {
    ClientRateLimitProperties properties = new ClientRateLimitProperties(true, 5, 60);
    filter = new ClientRateLimitFilter(properties, meterRegistry);
    filterChain = mock(FilterChain.class);
  }

  @Test
  @DisplayName("allows requests within limit and sets ratelimit headers")
  void allowsRequestsWithinLimitAndSetsRatelimitHeaders() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/catalog/products/1");
    request.setRemoteAddr("192.168.1.10");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("5");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("4");
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  @DisplayName("blocks requests exceeding limit with HTTP 429 and Retry-After header")
  void blocksRequestsExceedingLimitWithHttp429AndRetryAfterHeader() throws Exception {
    for (int i = 0; i < 5; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/catalog/products/1");
      request.setRemoteAddr("10.0.0.1");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, filterChain);
      assertThat(response.getStatus()).isEqualTo(200);
    }

    // 6th request from same IP should be blocked
    MockHttpServletRequest blockedRequest =
        new MockHttpServletRequest("GET", "/catalog/products/1");
    blockedRequest.setRemoteAddr("10.0.0.1");
    MockHttpServletResponse blockedResponse = new MockHttpServletResponse();

    filter.doFilter(blockedRequest, blockedResponse, filterChain);

    assertThat(blockedResponse.getStatus()).isEqualTo(429);
    assertThat(blockedResponse.getHeader("Retry-After")).isEqualTo("60");
    assertThat(blockedResponse.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    assertThat(blockedResponse.getContentAsString()).contains("Too Many Requests");
  }

  @Test
  @DisplayName("separate clients have independent rate limit quotas")
  void separateClientsHaveIndependentRateLimitQuotas() throws Exception {
    for (int i = 0; i < 5; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/catalog/products/1");
      request.setRemoteAddr("10.0.0.1");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, filterChain);
    }

    // Client 2 should NOT be blocked
    MockHttpServletRequest client2Request =
        new MockHttpServletRequest("GET", "/catalog/products/1");
    client2Request.setRemoteAddr("10.0.0.2");
    MockHttpServletResponse client2Response = new MockHttpServletResponse();

    filter.doFilter(client2Request, client2Response, filterChain);
    assertThat(client2Response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("actuator endpoints are excluded from rate limiting")
  void actuatorEndpointsAreExcludedFromRateLimiting() throws Exception {
    for (int i = 0; i < 10; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
      request.setRemoteAddr("10.0.0.1");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, filterChain);
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }
}
