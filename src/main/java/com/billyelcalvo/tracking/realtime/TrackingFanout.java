package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.assignment.ActiveAssignmentRegistry;
import com.billyelcalvo.tracking.location.LocationListener;
import com.billyelcalvo.tracking.location.LocationPing;
import com.billyelcalvo.tracking.order.OrderStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges ingestion and order events to the hub. Location pings only go out if the courier is
 * carrying an order; a free courier moving around is nobody's business.
 */
@Component
public class TrackingFanout implements LocationListener {

  private final ActiveAssignmentRegistry assignments;
  private final TrackingHub hub;

  public TrackingFanout(ActiveAssignmentRegistry assignments, TrackingHub hub) {
    this.assignments = assignments;
    this.hub = hub;
  }

  @Override
  public void onLocation(LocationPing ping) {
    assignments
        .activeOrderFor(ping.courierId())
        .ifPresent(orderId -> hub.publish(orderId, TrackingMessage.location(orderId, ping)));
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onStatusChanged(OrderStatusChanged event) {
    hub.publish(
        event.orderId(),
        TrackingMessage.status(event.orderId(), event.courierId(), event.status(), event.at()));
    if (event.status().isTerminal()) {
      hub.complete(event.orderId());
    }
  }
}
