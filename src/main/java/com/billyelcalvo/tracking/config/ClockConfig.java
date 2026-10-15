package com.billyelcalvo.tracking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A shared Clock bean keeps time-dependent code testable without sleeping in tests. */
@Configuration
class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
