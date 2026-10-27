package com.hyperscale.commerce.config.observability;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.tracing.zipkin")
public record ZipkinExportProperties(URI endpoint) {
  public ZipkinExportProperties {
    if (endpoint != null
        && (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
            || endpoint.getHost() == null
            || endpoint.getUserInfo() != null)) {
      throw new IllegalArgumentException("Zipkin endpoint must be HTTP(S) without credentials");
    }
  }
}
