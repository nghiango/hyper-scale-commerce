package com.hyperscale.commerce.orderquery.config.datasource

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.datasource")
data class DataSourceRoutingProperties(
    @field:Valid val primary: PrimaryPoolProperties = PrimaryPoolProperties(),
    @field:Valid val replica: ReplicaPoolProperties = ReplicaPoolProperties(),
) {
  data class PrimaryPoolProperties(
      val url: String = "",
      @field:Min(1) val maximumPoolSize: Int = 30,
  )

  data class ReplicaPoolProperties(
      val url: String = "",
      @field:Min(1) val maximumPoolSize: Int = 20,
      @field:Min(1) val maxLagMs: Long = 100,
      @field:Min(1) val lagCheckIntervalMs: Long = 1000,
  )
}
