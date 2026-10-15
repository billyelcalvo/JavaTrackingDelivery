package com.billyelcalvo.tracking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Boot's TaskScheduler; the flusher and the assignment retry job use it. */
@Configuration
@EnableScheduling
class SchedulingConfig {}
