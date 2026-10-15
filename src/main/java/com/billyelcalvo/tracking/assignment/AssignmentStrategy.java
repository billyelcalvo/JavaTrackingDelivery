package com.billyelcalvo.tracking.assignment;

import com.billyelcalvo.tracking.order.DeliveryOrder;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks which courier should take an order. Implementations must run inside the caller's
 * transaction and leave the chosen courier row locked, so nobody else can grab it before the
 * assignment commits.
 *
 * <p>Today there's only "nearest available". Keeping it behind an interface means trying something
 * smarter (ETA by road, courier rating, batching orders) is a new class, not a rewrite.
 */
public interface AssignmentStrategy {

  Optional<UUID> pickCourier(DeliveryOrder order);
}
