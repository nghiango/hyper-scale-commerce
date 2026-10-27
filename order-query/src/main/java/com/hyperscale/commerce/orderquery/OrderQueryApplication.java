package com.hyperscale.commerce.orderquery;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderQueryApplication {

  public static void main(String[] args) {
    new SpringApplicationBuilder(OrderQueryApplication.class)
        .properties("spring.config.name=orderquery")
        .run(args);
  }
}
