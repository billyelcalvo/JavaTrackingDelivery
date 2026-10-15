package com.billyelcalvo.tracking.order.api;

import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.order.api.OrderDtos.OrderResponse;
import com.billyelcalvo.tracking.security.CourierAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/orders")
@Tag(name = "Courier app")
@SecurityRequirement(name = "courierToken")
public class CourierOrderController {

  private final OrderService service;

  public CourierOrderController(OrderService service) {
    this.service = service;
  }

  @GetMapping("/current")
  @Operation(summary = "The order this courier is carrying, 204 if none")
  public ResponseEntity<OrderResponse> current(
      @Parameter(hidden = true) @RequestAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)
          UUID courierId) {
    return service
        .currentFor(courierId)
        .map(OrderResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/{id}/pickup")
  @Operation(summary = "Confirm the package was picked up")
  public OrderResponse pickup(
      @Parameter(hidden = true) @RequestAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)
          UUID courierId,
      @PathVariable UUID id) {
    return OrderResponse.from(service.markPickedUp(courierId, id));
  }

  @PostMapping("/{id}/deliver")
  @Operation(summary = "Confirm delivery; the courier becomes AVAILABLE again")
  public OrderResponse deliver(
      @Parameter(hidden = true) @RequestAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)
          UUID courierId,
      @PathVariable UUID id) {
    return OrderResponse.from(service.markDelivered(courierId, id));
  }
}
