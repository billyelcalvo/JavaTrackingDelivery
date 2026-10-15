package com.billyelcalvo.tracking.courier.api;

import com.billyelcalvo.tracking.courier.CourierService;
import com.billyelcalvo.tracking.courier.api.CourierDtos.ChangeStatusRequest;
import com.billyelcalvo.tracking.courier.api.CourierDtos.CourierResponse;
import com.billyelcalvo.tracking.security.CourierAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints the courier app calls with its own token. The courier id always comes from the JWT. */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Courier app", description = "Called by the courier with its bearer token")
@SecurityRequirement(name = "courierToken")
public class CourierSelfController {

  private final CourierService service;

  public CourierSelfController(CourierService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Current courier profile")
  public CourierResponse me(
      @Parameter(hidden = true) @RequestAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)
          UUID courierId) {
    return CourierResponse.from(service.get(courierId));
  }

  @PutMapping("/status")
  @Operation(summary = "Go AVAILABLE or OFFLINE")
  public CourierResponse changeStatus(
      @Parameter(hidden = true) @RequestAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)
          UUID courierId,
      @Valid @RequestBody ChangeStatusRequest request) {
    return CourierResponse.from(service.changeAvailability(courierId, request.status()));
  }
}
