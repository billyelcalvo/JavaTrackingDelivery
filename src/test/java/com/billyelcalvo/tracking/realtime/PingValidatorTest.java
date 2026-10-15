package com.billyelcalvo.tracking.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PingValidatorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private final PingValidator validator = new PingValidator(Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID courier = UUID.randomUUID();

  @Test
  void acceptsAValidPingAndUsesTheDeviceTimestamp() {
    long ts = NOW.minusSeconds(2).toEpochMilli();

    var result = validator.validate(courier, new CourierPingMessage(-12.05, -77.04, ts));

    assertThat(result.isAccepted()).isTrue();
    assertThat(result.ping().courierId()).isEqualTo(courier);
    assertThat(result.ping().recordedAt()).isEqualTo(Instant.ofEpochMilli(ts));
  }

  @Test
  void fallsBackToServerTimeWithoutTimestamp() {
    var result = validator.validate(courier, new CourierPingMessage(-12.05, -77.04, null));

    assertThat(result.ping().recordedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsMissingCoordinates() {
    assertThat(
            validator
                .validate(courier, new CourierPingMessage(null, -77.0, null))
                .rejectionReason())
        .isEqualTo("missing_coordinates");
    assertThat(validator.validate(courier, null).rejectionReason())
        .isEqualTo("missing_coordinates");
  }

  @Test
  void rejectsOutOfRangeCoordinates() {
    assertThat(
            validator.validate(courier, new CourierPingMessage(91.0, 0.0, null)).rejectionReason())
        .isEqualTo("out_of_range");
  }

  @Test
  void rejectsTimestampsTooFarInTheFutureOrPast() {
    long future = NOW.plus(PingValidator.MAX_CLOCK_SKEW).plusSeconds(1).toEpochMilli();
    long past = NOW.minus(PingValidator.MAX_AGE).minusSeconds(1).toEpochMilli();

    assertThat(
            validator.validate(courier, new CourierPingMessage(0.0, 0.0, future)).rejectionReason())
        .isEqualTo("timestamp_in_future");
    assertThat(
            validator.validate(courier, new CourierPingMessage(0.0, 0.0, past)).rejectionReason())
        .isEqualTo("timestamp_too_old");
  }
}
