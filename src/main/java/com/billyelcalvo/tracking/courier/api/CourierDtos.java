package com.billyelcalvo.tracking.courier.api;

import com.billyelcalvo.tracking.courier.Courier;
import com.billyelcalvo.tracking.courier.CourierService.RegisteredCourier;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.courier.NearbyCourier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class CourierDtos {

  private CourierDtos() {}

  public record RegisterCourierRequest(
      @Schema(example = "Ana Torres") @NotBlank @Size(max = 120) String name) {}

  public record RegisterCourierResponse(
      UUID id,
      String name,
      CourierStatus status,
      @Schema(description = "JWT for the courier app (WebSocket + /api/me)") String token,
      Instant tokenExpiresAt) {

    static RegisterCourierResponse from(RegisteredCourier registered) {
      Courier c = registered.courier();
      return new RegisterCourierResponse(
          c.id(), c.name(), c.status(), registered.token().token(), registered.token().expiresAt());
    }
  }

  public record CourierResponse(UUID id, String name, CourierStatus status, Instant createdAt) {

    static CourierResponse from(Courier c) {
      return new CourierResponse(c.id(), c.name(), c.status(), c.createdAt());
    }
  }

  public record ChangeStatusRequest(
      @Schema(allowableValues = {"AVAILABLE", "OFFLINE"}) @NotNull CourierStatus status) {}

  public record NearbyCourierResponse(
      UUID id,
      String name,
      CourierStatus status,
      double lat,
      double lng,
      long distanceMeters,
      Instant recordedAt) {

    static NearbyCourierResponse from(NearbyCourier n) {
      return new NearbyCourierResponse(
          n.id(),
          n.name(),
          n.status(),
          n.location().lat(),
          n.location().lng(),
          Math.round(n.distanceMeters()),
          n.recordedAt());
    }
  }
}
