package com.billyelcalvo.tracking.location;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.time.Instant;
import java.util.UUID;

/** One accepted position report from a courier. {@code recordedAt} is the device timestamp. */
public record LocationPing(UUID courierId, GeoPoint point, Instant recordedAt) {

  public boolean isNewerThan(LocationPing other) {
    return other == null || recordedAt.isAfter(other.recordedAt);
  }
}
