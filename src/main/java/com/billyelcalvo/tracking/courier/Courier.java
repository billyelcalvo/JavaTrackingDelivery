package com.billyelcalvo.tracking.courier;

import java.time.Instant;
import java.util.UUID;

public record Courier(UUID id, String name, CourierStatus status, Instant createdAt) {}
