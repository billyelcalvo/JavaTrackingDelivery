package com.billyelcalvo.tracking.order;

import com.billyelcalvo.tracking.common.ConflictException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. The allowed transitions live next to the states so there's exactly one place to
 * look when someone asks "can an order go from X to Y?".
 */
public enum OrderStatus {
  PENDING,
  ASSIGNED,
  PICKED_UP,
  DELIVERED,
  CANCELLED;

  private Set<OrderStatus> next;

  static {
    PENDING.next = EnumSet.of(ASSIGNED, CANCELLED);
    ASSIGNED.next = EnumSet.of(PICKED_UP, CANCELLED);
    PICKED_UP.next = EnumSet.of(DELIVERED);
    DELIVERED.next = EnumSet.noneOf(OrderStatus.class);
    CANCELLED.next = EnumSet.noneOf(OrderStatus.class);
  }

  public boolean canTransitionTo(OrderStatus target) {
    return next.contains(target);
  }

  public void ensureCanTransitionTo(OrderStatus target) {
    if (!canTransitionTo(target)) {
      throw new ConflictException("Order cannot go from " + this + " to " + target);
    }
  }

  /** The courier is still attached to the order in these states. */
  public boolean isActive() {
    return this == ASSIGNED || this == PICKED_UP;
  }

  public boolean isTerminal() {
    return next.isEmpty();
  }
}
