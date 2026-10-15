package com.billyelcalvo.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrackingDeliveryApplication {

  public static void main(String[] args) {
    SpringApplication.run(TrackingDeliveryApplication.class, args);
  }
}
