package com.billyelcalvo.tracking.order.api;

import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.order.api.OrderDtos.CreateOrderRequest;
import com.billyelcalvo.tracking.order.api.OrderDtos.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Create orders and follow them")
@SecurityRequirement(name = "apiKey")
public class OrderController {

  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create an order",
      description =
          "Assigns the nearest available courier right away. If nobody is in range the order"
              + " comes back PENDING and a background job keeps retrying.")
  public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
    return OrderResponse.from(
        service.create(request.pickup().toPoint(), request.dropoff().toPoint()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Order status plus the courier's last known position")
  public OrderResponse get(@PathVariable UUID id) {
    return OrderResponse.from(service.view(id));
  }

  @PostMapping("/{id}/cancel")
  @Operation(summary = "Cancel a pending or assigned order (frees the courier)")
  public OrderResponse cancel(@PathVariable UUID id) {
    return OrderResponse.from(service.cancel(id));
  }
}
