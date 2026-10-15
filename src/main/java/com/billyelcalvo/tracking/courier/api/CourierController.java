package com.billyelcalvo.tracking.courier.api;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.courier.CourierService;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.courier.api.CourierDtos.CourierResponse;
import com.billyelcalvo.tracking.courier.api.CourierDtos.NearbyCourierResponse;
import com.billyelcalvo.tracking.courier.api.CourierDtos.RegisterCourierRequest;
import com.billyelcalvo.tracking.courier.api.CourierDtos.RegisterCourierResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/couriers")
@Tag(name = "Couriers", description = "Back-office courier management")
@SecurityRequirement(name = "apiKey")
public class CourierController {

  private final CourierService service;

  public CourierController(CourierService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register a courier and issue its app token")
  public RegisterCourierResponse register(@Valid @RequestBody RegisterCourierRequest request) {
    return RegisterCourierResponse.from(service.register(request.name()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a courier")
  public CourierResponse get(@PathVariable UUID id) {
    return CourierResponse.from(service.get(id));
  }

  @GetMapping("/nearby")
  @Operation(summary = "Couriers around a point, nearest first (uses the PostGIS GiST index)")
  public List<NearbyCourierResponse> nearby(
      @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
      @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
      @RequestParam(defaultValue = "2000") @Min(1) @Max(50_000) int radiusMeters,
      @RequestParam(required = false) CourierStatus status,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return service.nearby(new GeoPoint(lat, lng), radiusMeters, status, limit).stream()
        .map(NearbyCourierResponse::from)
        .toList();
  }
}
