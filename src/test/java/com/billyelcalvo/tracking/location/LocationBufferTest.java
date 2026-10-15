package com.billyelcalvo.tracking.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocationBufferTest {

  private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

  private static LocationPing ping(UUID courier, int secondsAfterT0) {
    return new LocationPing(courier, new GeoPoint(-12.0, -77.0), T0.plusSeconds(secondsAfterT0));
  }

  @Test
  void keepsOnlyTheNewestPositionPerCourier() {
    var buffer = new LocationBuffer(100);
    UUID courier = UUID.randomUUID();

    buffer.add(ping(courier, 1));
    buffer.add(ping(courier, 3));
    buffer.add(ping(courier, 2)); // arrives late, must not win

    assertThat(buffer.drainLatest())
        .singleElement()
        .extracting(LocationPing::recordedAt)
        .isEqualTo(T0.plusSeconds(3));
    assertThat(buffer.drainHistory(10)).hasSize(3);
  }

  @Test
  void drainEmptiesTheBuffer() {
    var buffer = new LocationBuffer(100);
    buffer.add(ping(UUID.randomUUID(), 1));
    buffer.add(ping(UUID.randomUUID(), 1));

    assertThat(buffer.drainLatest()).hasSize(2);
    assertThat(buffer.drainLatest()).isEmpty();
    assertThat(buffer.latestSize()).isZero();
  }

  @Test
  void historyDropsWhenFullButLatestIsAlwaysKept() {
    var buffer = new LocationBuffer(2);
    UUID courier = UUID.randomUUID();

    assertThat(buffer.add(ping(courier, 1))).isTrue();
    assertThat(buffer.add(ping(courier, 2))).isTrue();
    assertThat(buffer.add(ping(courier, 3))).isFalse();

    assertThat(buffer.historySize()).isEqualTo(2);
    assertThat(buffer.pendingLatest(courier))
        .get()
        .extracting(LocationPing::recordedAt)
        .isEqualTo(T0.plusSeconds(3));
  }

  @Test
  void drainHistoryRespectsTheBatchSize() {
    var buffer = new LocationBuffer(100);
    UUID courier = UUID.randomUUID();
    for (int i = 0; i < 7; i++) {
      buffer.add(ping(courier, i));
    }

    assertThat(buffer.drainHistory(5)).hasSize(5);
    assertThat(buffer.drainHistory(5)).hasSize(2);
    assertThat(buffer.drainHistory(5)).isEmpty();
  }

  @Test
  void requeueDoesNotOverwriteANewerPing() {
    var buffer = new LocationBuffer(100);
    UUID courier = UUID.randomUUID();
    buffer.add(ping(courier, 1));
    List<LocationPing> inFlight = buffer.drainLatest();

    buffer.add(ping(courier, 5)); // arrives while the failed flush was running
    buffer.requeueLatest(inFlight);

    assertThat(buffer.pendingLatest(courier))
        .get()
        .extracting(LocationPing::recordedAt)
        .isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void requeueHistoryReportsWhatDidNotFit() {
    var buffer = new LocationBuffer(1);
    UUID courier = UUID.randomUUID();

    int dropped = buffer.requeueHistory(List.of(ping(courier, 1), ping(courier, 2)));

    assertThat(dropped).isEqualTo(1);
  }

  @Test
  void noPingIsLostUnderConcurrentWritesAndDrains() throws Exception {
    var buffer = new LocationBuffer(1_000_000);
    int couriers = 50;
    int pingsPerCourier = 200;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch done = new CountDownLatch(couriers);
    var lastSeen = new java.util.concurrent.ConcurrentHashMap<UUID, Instant>();

    for (int c = 0; c < couriers; c++) {
      UUID id = UUID.randomUUID();
      pool.submit(
          () -> {
            for (int i = 1; i <= pingsPerCourier; i++) {
              buffer.add(ping(id, i));
            }
            done.countDown();
          });
    }
    // Drain concurrently while writers are busy, like the scheduled flusher does
    while (!done.await(1, TimeUnit.MILLISECONDS)) {
      buffer
          .drainLatest()
          .forEach(
              p -> lastSeen.merge(p.courierId(), p.recordedAt(), (a, b) -> a.isAfter(b) ? a : b));
    }
    buffer
        .drainLatest()
        .forEach(
            p -> lastSeen.merge(p.courierId(), p.recordedAt(), (a, b) -> a.isAfter(b) ? a : b));
    pool.shutdown();

    assertThat(lastSeen).hasSize(couriers);
    assertThat(lastSeen.values()).allMatch(t -> t.equals(T0.plusSeconds(pingsPerCourier)));
    assertThat(buffer.drainHistory(Integer.MAX_VALUE)).hasSize(couriers * pingsPerCourier);
  }
}
