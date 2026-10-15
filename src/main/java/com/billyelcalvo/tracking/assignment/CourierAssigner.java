package com.billyelcalvo.tracking.assignment;

import com.billyelcalvo.tracking.courier.CourierRepository;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderRepository;
import com.billyelcalvo.tracking.order.OrderStatus;
import com.billyelcalvo.tracking.order.OrderStatusChanged;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Glues a strategy's pick to the actual state changes on courier and order. */
@Service
public class CourierAssigner {

  private static final Logger log = LoggerFactory.getLogger(CourierAssigner.class);

  private final AssignmentStrategy strategy;
  private final CourierRepository couriers;
  private final OrderRepository orders;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Timer pickTimer;
  private final Counter assigned;
  private final Counter noCourier;

  public CourierAssigner(
      AssignmentStrategy strategy,
      CourierRepository couriers,
      OrderRepository orders,
      ApplicationEventPublisher events,
      Clock clock,
      MeterRegistry meters) {
    this.strategy = strategy;
    this.couriers = couriers;
    this.orders = orders;
    this.events = events;
    this.clock = clock;
    this.pickTimer = meters.timer("tracking.assignment.pick");
    this.assigned = meters.counter("tracking.assignment.result", "outcome", "assigned");
    this.noCourier = meters.counter("tracking.assignment.result", "outcome", "no_courier");
  }

  /**
   * Tries to assign a PENDING order. Returns the updated order, or the same one untouched if no
   * courier is around. Must join an existing transaction: the courier lock taken by the strategy is
   * only worth something if the updates below commit together with it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public DeliveryOrder tryAssign(DeliveryOrder order) {
    if (order.status() != OrderStatus.PENDING) {
      return order;
    }
    Optional<UUID> courierId = pickTimer.record(() -> strategy.pickCourier(order));
    if (courierId.isEmpty()) {
      noCourier.increment();
      log.debug("No courier available for order orderId={}", order.id());
      return order;
    }
    // The row is locked by the strategy, so this can only fail if the strategy has a bug
    if (!couriers.updateStatus(courierId.get(), CourierStatus.AVAILABLE, CourierStatus.BUSY)) {
      throw new IllegalStateException("Picked courier " + courierId.get() + " is not available");
    }
    DeliveryOrder updated = orders.assign(order.id(), courierId.get());
    assigned.increment();
    events.publishEvent(
        new OrderStatusChanged(
            updated.id(), updated.courierId(), OrderStatus.ASSIGNED, clock.instant()));
    log.info("Order assigned orderId={} courierId={}", updated.id(), updated.courierId());
    return updated;
  }
}
