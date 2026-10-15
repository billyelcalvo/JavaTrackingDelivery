package com.billyelcalvo.tracking.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.courier.CourierRepository;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderRepository;
import com.billyelcalvo.tracking.order.OrderStatus;
import com.billyelcalvo.tracking.order.OrderStatusChanged;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CourierAssignerTest {

  private final AssignmentStrategy strategy = mock(AssignmentStrategy.class);
  private final CourierRepository couriers = mock(CourierRepository.class);
  private final OrderRepository orders = mock(OrderRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final CourierAssigner assigner =
      new CourierAssigner(
          strategy, couriers, orders, events, Clock.systemUTC(), new SimpleMeterRegistry());

  private static DeliveryOrder order(OrderStatus status, UUID courierId) {
    GeoPoint p = new GeoPoint(-12, -77);
    return new DeliveryOrder(
        UUID.randomUUID(), status, p, p, courierId, Instant.EPOCH, null, null, null, null);
  }

  @Test
  void assignsThePickedCourierAndPublishesTheEvent() {
    DeliveryOrder pending = order(OrderStatus.PENDING, null);
    UUID courier = UUID.randomUUID();
    DeliveryOrder assigned =
        new DeliveryOrder(
            pending.id(),
            OrderStatus.ASSIGNED,
            pending.pickup(),
            pending.dropoff(),
            courier,
            pending.createdAt(),
            Instant.now(),
            null,
            null,
            null);
    when(strategy.pickCourier(pending)).thenReturn(Optional.of(courier));
    when(couriers.updateStatus(courier, CourierStatus.AVAILABLE, CourierStatus.BUSY))
        .thenReturn(true);
    when(orders.assign(pending.id(), courier)).thenReturn(assigned);

    DeliveryOrder result = assigner.tryAssign(pending);

    assertThat(result.status()).isEqualTo(OrderStatus.ASSIGNED);
    verify(events)
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(
                e ->
                    e instanceof OrderStatusChanged c
                        && c.courierId().equals(courier)
                        && c.status() == OrderStatus.ASSIGNED));
  }

  @Test
  void leavesTheOrderPendingWhenNobodyIsAround() {
    DeliveryOrder pending = order(OrderStatus.PENDING, null);
    when(strategy.pickCourier(pending)).thenReturn(Optional.empty());

    assertThat(assigner.tryAssign(pending)).isSameAs(pending);
    verify(orders, never()).assign(any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void skipsOrdersThatAreNotPending() {
    DeliveryOrder assigned = order(OrderStatus.ASSIGNED, UUID.randomUUID());

    assertThat(assigner.tryAssign(assigned)).isSameAs(assigned);
    verifyNoInteractions(strategy);
  }

  @Test
  void failsLoudlyIfThePickedCourierIsNoLongerAvailable() {
    DeliveryOrder pending = order(OrderStatus.PENDING, null);
    UUID courier = UUID.randomUUID();
    when(strategy.pickCourier(pending)).thenReturn(Optional.of(courier));
    when(couriers.updateStatus(courier, CourierStatus.AVAILABLE, CourierStatus.BUSY))
        .thenReturn(false);

    assertThatThrownBy(() -> assigner.tryAssign(pending)).isInstanceOf(IllegalStateException.class);
    verify(orders, never()).assign(any(), any());
  }
}
