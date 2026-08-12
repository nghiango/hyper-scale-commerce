package com.hyperscale.commerce.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

  @Bean
  fun openAPI(appProperties: AppProperties): OpenAPI =
      OpenAPI()
          .info(
              Info()
                  .title(appProperties.name)
                  .description("HyperScale Commerce API")
                  .version("0.0.1-SNAPSHOT"),
          )
}
