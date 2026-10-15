package com.billyelcalvo.tracking.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.TestFixtures;
import com.billyelcalvo.tracking.config.TrackingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourierTokenServiceTest {

  private final TrackingProperties props = TestFixtures.properties();

  @Test
  void issuedTokenVerifiesBackToTheSameCourier() {
    var service = new CourierTokenService(props, Clock.systemUTC());
    UUID courier = UUID.randomUUID();

    var issued = service.issue(courier);

    assertThat(service.verify(issued.token())).contains(courier);
    assertThat(issued.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void rejectsGarbageBlankAndNull() {
    var service = new CourierTokenService(props, Clock.systemUTC());

    assertThat(service.verify("not-a-jwt")).isEmpty();
    assertThat(service.verify("")).isEmpty();
    assertThat(service.verify(null)).isEmpty();
  }

  @Test
  void rejectsTokenSignedWithAnotherSecret() {
    var other =
        new TrackingProperties(
            new TrackingProperties.Security(
                "k", "another-secret-another-secret-another-1", Duration.ofHours(1)),
            props.ingestion(),
            props.assignment(),
            props.websocket());
    String foreign =
        new CourierTokenService(other, Clock.systemUTC()).issue(UUID.randomUUID()).token();

    assertThat(new CourierTokenService(props, Clock.systemUTC()).verify(foreign)).isEmpty();
  }

  @Test
  void rejectsExpiredToken() {
    Instant longAgo = Instant.now().minus(Duration.ofDays(2));
    var issuer = new CourierTokenService(props, Clock.fixed(longAgo, ZoneOffset.UTC));
    String expired = issuer.issue(UUID.randomUUID()).token();

    assertThat(new CourierTokenService(props, Clock.systemUTC()).verify(expired)).isEmpty();
  }
}
