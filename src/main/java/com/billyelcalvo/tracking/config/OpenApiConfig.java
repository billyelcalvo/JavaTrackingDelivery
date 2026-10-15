package com.billyelcalvo.tracking.config;

import com.billyelcalvo.tracking.security.ApiKeyFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI trackingOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Tracking Delivery API")
                .version("v1")
                .description(
                    """
                    REST side of the real-time delivery tracker. Live traffic goes through \
                    WebSockets, which OpenAPI can't describe, so they are documented in the \
                    README: `/ws/couriers` (courier pings) and `/ws/tracking?orderId=` \
                    (customer live view)."""))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyFilter.HEADER))
                .addSecuritySchemes(
                    "courierToken",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
