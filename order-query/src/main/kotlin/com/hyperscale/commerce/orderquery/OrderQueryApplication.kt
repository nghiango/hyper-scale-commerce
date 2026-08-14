package com.hyperscale.commerce.orderquery

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication @ConfigurationPropertiesScan class OrderQueryApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
  SpringApplicationBuilder(OrderQueryApplication::class.java)
      .properties("spring.config.name=orderquery")
      .run(*args)
}
