package com.hyperscale.commerce.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openAPI(AppProperties appProperties) {
    return new OpenAPI().info(new Info().title(appProperties.name()).description("HyperScale Commerce API")
        .version("0.0.1-SNAPSHOT"));
  }
}
