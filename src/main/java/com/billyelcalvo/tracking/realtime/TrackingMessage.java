package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.location.LocationPing;
import com.billyelcalvo.tracking.order.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** What a customer receives on /ws/tracking. Flat on purpose: easy to parse on any client. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackingMessage(
    String type,
    UUID orderId,
    UUID courierId,
    OrderStatus status,
    Double lat,
    Double lng,
    Instant at) {

  public static final String LOCATION = "location";
  public static final String STATUS = "status";

  public static TrackingMessage location(UUID orderId, LocationPing ping) {
    return new TrackingMessage(
        LOCATION,
        orderId,
        ping.courierId(),
        null,
        ping.point().lat(),
        ping.point().lng(),
        ping.recordedAt());
  }

  public static TrackingMessage status(
      UUID orderId, UUID courierId, OrderStatus status, Instant at) {
    return new TrackingMessage(STATUS, orderId, courierId, status, null, null, at);
  }
}
