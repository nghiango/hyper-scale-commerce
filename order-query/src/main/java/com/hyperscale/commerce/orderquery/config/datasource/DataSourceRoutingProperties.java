package com.hyperscale.commerce.orderquery.config.datasource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.datasource")
public record DataSourceRoutingProperties(
    @Valid @DefaultValue PrimaryPoolProperties primary,
    @Valid @DefaultValue ReplicaPoolProperties replica) {

  public DataSourceRoutingProperties {
    if (primary == null) {
      primary = new PrimaryPoolProperties("", 30);
    }
    if (replica == null) {
      replica = new ReplicaPoolProperties("", 20, 100L, 1000L);
    }
  }

  public record PrimaryPoolProperties(
      @DefaultValue("") String url, @Min(1) @DefaultValue("30") int maximumPoolSize) {}

  public record ReplicaPoolProperties(
      @DefaultValue("") String url,
      @Min(1) @DefaultValue("20") int maximumPoolSize,
      @Min(1) @DefaultValue("100") long maxLagMs,
      @Min(1) @DefaultValue("1000") long lagCheckIntervalMs) {}
}
