package com.billyelcalvo.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.assignment.PendingOrderRetryJob;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.order.OrderStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AssignmentIntegrationTest extends IntegrationTest {

  // Around Miraflores, Lima. 0.001 deg of latitude is ~111 m.
  static final GeoPoint PICKUP = new GeoPoint(-12.1211, -77.0297);
  static final GeoPoint DROPOFF = new GeoPoint(-12.1300, -77.0200);

  @Autowired OrderService orders;
  @Autowired PendingOrderRetryJob retryJob;

  private GeoPoint north(double meters) {
    return new GeoPoint(PICKUP.lat() + meters / 111_000.0, PICKUP.lng());
  }

  @Test
  void picksTheNearestAvailableCourier() {
    UUID far = TestDataFactory.courier(jdbc, "far", CourierStatus.AVAILABLE);
    UUID near = TestDataFactory.courier(jdbc, "near", CourierStatus.AVAILABLE);
    UUID nearestButOffline = TestDataFactory.courier(jdbc, "offline", CourierStatus.OFFLINE);
    reportPosition(far, north(1500));
    reportPosition(near, north(300));
    reportPosition(nearestButOffline, north(50));

    DeliveryOrder order = orders.create(PICKUP, DROPOFF);

    assertThat(order.status()).isEqualTo(OrderStatus.ASSIGNED);
    assertThat(order.courierId()).isEqualTo(near);
    assertThat(courierStatus(near)).isEqualTo("BUSY");
    assertThat(registry.activeOrderFor(near)).contains(order.id());
  }

  @Test
  void ignoresCouriersOutsideTheRadiusOrWithStalePositions() {
    UUID outside = TestDataFactory.courier(jdbc, "outside", CourierStatus.AVAILABLE);
    UUID stale = TestDataFactory.courier(jdbc, "stale", CourierStatus.AVAILABLE);
    reportPosition(outside, north(6000)); // radius is 5 km by default
    reportPosition(stale, north(100));
    TestDataFactory.ageLocation(jdbc, stale, 120); // freshness window is 60 s

    DeliveryOrder order = orders.create(PICKUP, DROPOFF);

    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.courierId()).isNull();
  }

  @Test
  void retryJobAssignsPendingOrdersOnceSomeoneShowsUp() {
    DeliveryOrder first = orders.create(PICKUP, DROPOFF);
    DeliveryOrder second = orders.create(PICKUP, DROPOFF);
    assertThat(first.status()).isEqualTo(OrderStatus.PENDING);

    UUID courier = TestDataFactory.courier(jdbc, "late", CourierStatus.AVAILABLE);
    reportPosition(courier, north(200));

    assertThat(retryJob.runOnce()).isEqualTo(1);
    // Oldest first: the order that waited longer gets the only courier
    assertThat(orders.get(first.id()).courierId()).isEqualTo(courier);
    assertThat(orders.get(second.id()).status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void concurrentOrdersNeverShareACourier() throws Exception {
    int couriers = 10;
    int concurrentOrders = 25;
    for (int i = 0; i < couriers; i++) {
      UUID id = TestDataFactory.courier(jdbc, "c" + i, CourierStatus.AVAILABLE);
      reportPosition(id, north(50 + i * 20));
    }

    ExecutorService pool = Executors.newFixedThreadPool(concurrentOrders);
    List<Callable<DeliveryOrder>> tasks = new ArrayList<>();
    for (int i = 0; i < concurrentOrders; i++) {
      tasks.add(() -> orders.create(PICKUP, DROPOFF));
    }
    List<DeliveryOrder> results = new ArrayList<>();
    for (Future<DeliveryOrder> f : pool.invokeAll(tasks)) {
      results.add(f.get());
    }
    pool.shutdown();

    List<DeliveryOrder> assigned =
        results.stream().filter(o -> o.status() == OrderStatus.ASSIGNED).toList();
    Set<UUID> distinctCouriers = new HashSet<>();
    assigned.forEach(o -> distinctCouriers.add(o.courierId()));

    // Every courier got exactly one order, the rest wait. No double assignment, no deadlock.
    assertThat(assigned).hasSize(couriers);
    assertThat(distinctCouriers).hasSize(couriers);
    assertThat(results)
        .filteredOn(o -> o.status() == OrderStatus.PENDING)
        .hasSize(concurrentOrders - couriers);
    assertThat(
            jdbc.sql("SELECT count(*) FROM courier WHERE status = 'BUSY'")
                .query(Integer.class)
                .single())
        .isEqualTo(couriers);
  }

  private String courierStatus(UUID id) {
    return jdbc.sql("SELECT status FROM courier WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }
}
