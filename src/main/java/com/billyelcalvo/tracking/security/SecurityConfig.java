package com.billyelcalvo.tracking.security;

import com.billyelcalvo.tracking.config.TrackingProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SecurityConfig {

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(TrackingProperties properties) {
    var registration = new FilterRegistrationBean<>(new ApiKeyFilter(properties));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
    return registration;
  }

  @Bean
  FilterRegistrationBean<CourierAuthFilter> courierAuthFilter(CourierTokenService tokenService) {
    var registration = new FilterRegistrationBean<>(new CourierAuthFilter(tokenService));
    registration.addUrlPatterns("/api/me/*");
    registration.setOrder(2);
    return registration;
  }
}
