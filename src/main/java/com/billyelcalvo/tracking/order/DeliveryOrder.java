package com.billyelcalvo.tracking.order;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.time.Instant;
import java.util.UUID;

public record DeliveryOrder(
    UUID id,
    OrderStatus status,
    GeoPoint pickup,
    GeoPoint dropoff,
    UUID courierId,
    Instant createdAt,
    Instant assignedAt,
    Instant pickedUpAt,
    Instant deliveredAt,
    Instant cancelledAt) {}
