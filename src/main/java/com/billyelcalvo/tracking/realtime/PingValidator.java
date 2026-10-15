package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.location.LocationPing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a raw ping into a LocationPing or explains why not. Kept apart from the handler so the
 * rules can be unit tested without a WebSocket.
 */
public class PingValidator {

  /** Phones drift, but not by more than this. */
  static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);

  /** Pings that come back from a long offline buffer are useless for live tracking. */
  static final Duration MAX_AGE = Duration.ofMinutes(5);

  private final Clock clock;

  public PingValidator(Clock clock) {
    this.clock = clock;
  }

  public Result validate(UUID courierId, CourierPingMessage msg) {
    if (msg == null || msg.lat() == null || msg.lng() == null) {
      return Result.rejected("missing_coordinates");
    }
    GeoPoint point;
    try {
      point = new GeoPoint(msg.lat(), msg.lng());
    } catch (IllegalArgumentException ex) {
      return Result.rejected("out_of_range");
    }
    Instant now = clock.instant();
    Instant recordedAt = msg.ts() == null ? now : Instant.ofEpochMilli(msg.ts());
    if (recordedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
      return Result.rejected("timestamp_in_future");
    }
    if (recordedAt.isBefore(now.minus(MAX_AGE))) {
      return Result.rejected("timestamp_too_old");
    }
    return Result.accepted(new LocationPing(courierId, point, recordedAt));
  }

  public record Result(LocationPing ping, String rejectionReason) {

    static Result accepted(LocationPing ping) {
      return new Result(ping, null);
    }

    static Result rejected(String reason) {
      return new Result(null, reason);
    }

    public boolean isAccepted() {
      return ping != null;
    }
  }
}
