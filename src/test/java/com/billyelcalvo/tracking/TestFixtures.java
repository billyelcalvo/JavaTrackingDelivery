package com.billyelcalvo.tracking;

import com.billyelcalvo.tracking.config.TrackingProperties;
import java.time.Duration;
import java.util.List;

public final class TestFixtures {

  public static final String API_KEY = "test-api-key";
  public static final String JWT_SECRET = "test-secret-test-secret-test-secret-123";

  private TestFixtures() {}

  public static TrackingProperties properties() {
    return new TrackingProperties(
        new TrackingProperties.Security(API_KEY, JWT_SECRET, Duration.ofHours(1)),
        new TrackingProperties.Ingestion(Duration.ofMillis(500), 100, 50, Duration.ofMillis(500)),
        new TrackingProperties.Assignment(5000, Duration.ofSeconds(60), Duration.ofSeconds(5), 10),
        new TrackingProperties.WebSocket(Duration.ofSeconds(5), 65536, List.of("*")));
  }
}
