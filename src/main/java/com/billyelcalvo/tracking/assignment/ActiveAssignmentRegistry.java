package com.billyelcalvo.tracking.assignment;

import com.billyelcalvo.tracking.order.OrderRepository;
import com.billyelcalvo.tracking.order.OrderStatusChanged;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-memory courier -> active order map. Every ping needs to know "which order is this courier
 * carrying?" to fan out to the right customers, and asking Postgres thousands of times per second
 * would undo the whole point of buffering writes.
 *
 * <p>It's rebuilt from the database on startup and kept in sync with committed order events.
 */
@Component
public class ActiveAssignmentRegistry {

  private static final Logger log = LoggerFactory.getLogger(ActiveAssignmentRegistry.class);

  private final ConcurrentHashMap<UUID, UUID> orderByCourier = new ConcurrentHashMap<>();
  private final OrderRepository orders;

  public ActiveAssignmentRegistry(OrderRepository orders) {
    this.orders = orders;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reload() {
    orderByCourier.clear();
    orders.findActiveAssignments().forEach(a -> orderByCourier.put(a.courierId(), a.orderId()));
    log.atInfo().addKeyValue("count", orderByCourier.size()).log("Active assignments loaded");
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void on(OrderStatusChanged event) {
    if (event.courierId() == null) {
      return;
    }
    if (event.status().isActive()) {
      orderByCourier.put(event.courierId(), event.orderId());
    } else {
      orderByCourier.remove(event.courierId(), event.orderId());
    }
  }

  public Optional<UUID> activeOrderFor(UUID courierId) {
    return Optional.ofNullable(orderByCourier.get(courierId));
  }

  public int size() {
    return orderByCourier.size();
  }
}
