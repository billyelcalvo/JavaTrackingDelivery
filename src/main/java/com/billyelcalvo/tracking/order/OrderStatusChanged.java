package com.billyelcalvo.tracking.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that changed the order. Listeners use
 * {@code @TransactionalEventListener} so they only react once the change is actually committed.
 */
public record OrderStatusChanged(UUID orderId, UUID courierId, OrderStatus status, Instant at) {}
