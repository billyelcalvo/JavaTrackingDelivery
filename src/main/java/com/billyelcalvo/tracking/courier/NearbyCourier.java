package com.billyelcalvo.tracking.courier;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.time.Instant;
import java.util.UUID;

public record NearbyCourier(
    UUID id,
    String name,
    CourierStatus status,
    GeoPoint location,
    double distanceMeters,
    Instant recordedAt) {}
