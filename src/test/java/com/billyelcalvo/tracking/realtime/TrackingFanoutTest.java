package com.billyelcalvo.tracking.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.billyelcalvo.tracking.assignment.ActiveAssignmentRegistry;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.location.LocationPing;
import com.billyelcalvo.tracking.order.OrderStatus;
import com.billyelcalvo.tracking.order.OrderStatusChanged;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackingFanoutTest {

  private final ActiveAssignmentRegistry registry = mock(ActiveAssignmentRegistry.class);
  private final TrackingHub hub = mock(TrackingHub.class);
  private final TrackingFanout fanout = new TrackingFanout(registry, hub);
  private final UUID courier = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();
  private final LocationPing ping = new LocationPing(courier, new GeoPoint(1, 1), Instant.EPOCH);

  @Test
  void forwardsPingsOfCouriersCarryingAnOrder() {
    when(registry.activeOrderFor(courier)).thenReturn(Optional.of(order));

    fanout.onLocation(ping);

    verify(hub).publish(eq(order), argThat(m -> m.type().equals(TrackingMessage.LOCATION)));
  }

  @Test
  void ignoresFreeCouriers() {
    when(registry.activeOrderFor(courier)).thenReturn(Optional.empty());

    fanout.onLocation(ping);

    verify(hub, never()).publish(any(), any());
  }

  @Test
  void pushesStatusChangesAndClosesFinishedOrders() {
    fanout.onStatusChanged(
        new OrderStatusChanged(order, courier, OrderStatus.PICKED_UP, Instant.EPOCH));
    verify(hub).publish(eq(order), argThat(m -> m.status() == OrderStatus.PICKED_UP));
    verify(hub, never()).complete(order);

    fanout.onStatusChanged(
        new OrderStatusChanged(order, courier, OrderStatus.DELIVERED, Instant.EPOCH));
    verify(hub).complete(order);
  }
}
