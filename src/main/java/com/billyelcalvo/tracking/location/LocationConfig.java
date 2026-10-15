package com.billyelcalvo.tracking.location;

import com.billyelcalvo.tracking.config.TrackingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LocationConfig {

  @Bean
  LocationBuffer locationBuffer(TrackingProperties properties) {
    return new LocationBuffer(properties.ingestion().historyQueueCapacity());
  }
}
