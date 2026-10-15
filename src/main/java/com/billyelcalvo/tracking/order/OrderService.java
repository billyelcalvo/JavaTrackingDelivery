package com.billyelcalvo.tracking.order;

import com.billyelcalvo.tracking.assignment.CourierAssigner;
import com.billyelcalvo.tracking.common.ConflictException;
import com.billyelcalvo.tracking.common.ForbiddenException;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.common.NotFoundException;
import com.billyelcalvo.tracking.courier.CourierRepository;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.location.LocationIngestionService;
import com.billyelcalvo.tracking.location.LocationPing;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orders;
  private final CourierRepository couriers;
  private final CourierAssigner assigner;
  private final LocationIngestionService locations;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public OrderService(
      OrderRepository orders,
      CourierRepository couriers,
      CourierAssigner assigner,
      LocationIngestionService locations,
      ApplicationEventPublisher events,
      Clock clock) {
    this.orders = orders;
    this.couriers = couriers;
    this.assigner = assigner;
    this.locations = locations;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Creates the order and tries to assign it in the same transaction, so the common case (a courier
   * is nearby) answers ASSIGNED in a single round trip. If nobody is around the order is still
   * created as PENDING and the retry job picks it up later.
   */
  @Transactional
  public DeliveryOrder create(GeoPoint pickup, GeoPoint dropoff) {
    DeliveryOrder order = orders.insert(pickup, dropoff);
    log.info("Order created orderId={}", order.id());
    return assigner.tryAssign(order);
  }

  public OrderView view(UUID orderId) {
    DeliveryOrder order = get(orderId);
    Optional<LocationPing> courierLocation =
        order.status().isActive() ? locations.latest(order.courierId()) : Optional.empty();
    return new OrderView(order, courierLocation.orElse(null));
  }

  public DeliveryOrder get(UUID orderId) {
    return orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
  }

  public Optional<DeliveryOrder> currentFor(UUID courierId) {
    return orders.findActiveByCourier(courierId);
  }

  @Transactional
  public DeliveryOrder markPickedUp(UUID courierId, UUID orderId) {
    DeliveryOrder order = lockOwnedBy(courierId, orderId);
    return move(order, OrderStatus.PICKED_UP);
  }

  @Transactional
  public DeliveryOrder markDelivered(UUID courierId, UUID orderId) {
    DeliveryOrder order = lockOwnedBy(courierId, orderId);
    DeliveryOrder delivered = move(order, OrderStatus.DELIVERED);
    releaseCourier(courierId);
    return delivered;
  }

  @Transactional
  public DeliveryOrder cancel(UUID orderId) {
    DeliveryOrder order =
        orders
            .findByIdForUpdate(orderId)
            .orElseThrow(() -> new NotFoundException("Order not found"));
    DeliveryOrder cancelled = move(order, OrderStatus.CANCELLED);
    if (order.courierId() != null) {
      releaseCourier(order.courierId());
    }
    return cancelled;
  }

  private DeliveryOrder lockOwnedBy(UUID courierId, UUID orderId) {
    DeliveryOrder order =
        orders
            .findByIdForUpdate(orderId)
            .orElseThrow(() -> new NotFoundException("Order not found"));
    if (!courierId.equals(order.courierId())) {
      // Don't let one courier drive another courier's order
      throw new ForbiddenException("Order is not assigned to this courier");
    }
    return order;
  }

  private DeliveryOrder move(DeliveryOrder order, OrderStatus target) {
    order.status().ensureCanTransitionTo(target);
    DeliveryOrder updated =
        orders
            .transition(order.id(), order.status(), target)
            .orElseThrow(() -> new ConflictException("Order changed concurrently, retry"));
    events.publishEvent(
        new OrderStatusChanged(updated.id(), updated.courierId(), target, clock.instant()));
    log.info("Order status changed orderId={} status={}", updated.id(), target);
    return updated;
  }

  private void releaseCourier(UUID courierId) {
    if (!couriers.updateStatus(courierId, CourierStatus.BUSY, CourierStatus.AVAILABLE)) {
      log.warn("Courier was not BUSY when releasing courierId={}", courierId);
    }
  }

  public record OrderView(DeliveryOrder order, LocationPing courierLocation) {}
}
