package com.billyelcalvo.tracking.order.api;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.location.LocationPing;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderService.OrderView;
import com.billyelcalvo.tracking.order.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class OrderDtos {

  private OrderDtos() {}

  public record Coordinates(
      @Schema(example = "-12.0464") @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
      @Schema(example = "-77.0428") @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    GeoPoint toPoint() {
      return new GeoPoint(lat, lng);
    }

    static Coordinates of(GeoPoint p) {
      return p == null ? null : new Coordinates(p.lat(), p.lng());
    }
  }

  public record CreateOrderRequest(
      @Valid @NotNull Coordinates pickup, @Valid @NotNull Coordinates dropoff) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CourierPosition(double lat, double lng, Instant recordedAt) {

    static CourierPosition from(LocationPing ping) {
      return ping == null
          ? null
          : new CourierPosition(ping.point().lat(), ping.point().lng(), ping.recordedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OrderResponse(
      UUID id,
      OrderStatus status,
      Coordinates pickup,
      Coordinates dropoff,
      UUID courierId,
      CourierPosition courierPosition,
      Instant createdAt,
      Instant assignedAt,
      Instant pickedUpAt,
      Instant deliveredAt,
      Instant cancelledAt) {

    static OrderResponse from(DeliveryOrder o) {
      return from(o, null);
    }

    static OrderResponse from(OrderView view) {
      return from(view.order(), view.courierLocation());
    }

    private static OrderResponse from(DeliveryOrder o, LocationPing position) {
      return new OrderResponse(
          o.id(),
          o.status(),
          Coordinates.of(o.pickup()),
          Coordinates.of(o.dropoff()),
          o.courierId(),
          CourierPosition.from(position),
          o.createdAt(),
          o.assignedAt(),
          o.pickedUpAt(),
          o.deliveredAt(),
          o.cancelledAt());
    }
  }
}
