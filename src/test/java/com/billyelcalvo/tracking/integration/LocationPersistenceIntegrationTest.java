package com.billyelcalvo.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.location.CourierLocationRepository;
import com.billyelcalvo.tracking.location.LocationPing;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LocationPersistenceIntegrationTest extends IntegrationTest {

  @Autowired CourierLocationRepository repository;

  @Test
  void flushStoresLatestPositionAndEveryPingInHistory() {
    UUID courier = TestDataFactory.courier(jdbc, "Ana", CourierStatus.AVAILABLE);
    Instant t0 = Instant.now().minusSeconds(10);
    for (int i = 0; i < 5; i++) {
      ingestion.ingest(
          new LocationPing(courier, new GeoPoint(-12.0 - i * 0.001, -77.0), t0.plusSeconds(i)));
    }

    flusher.flushAndWait();

    LocationPing latest = repository.findLatest(courier).orElseThrow();
    assertThat(latest.point().lat()).isEqualTo(-12.004, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(repository.countHistory(courier)).isEqualTo(5);
  }

  @Test
  void anOlderPingNeverOverwritesANewerPosition() {
    UUID courier = TestDataFactory.courier(jdbc, "Ana", CourierStatus.AVAILABLE);
    Instant now = Instant.now();
    repository.upsertLatest(List.of(new LocationPing(courier, new GeoPoint(1, 1), now)));

    // A delayed packet from an earlier moment arrives after the newer one was stored
    repository.upsertLatest(
        List.of(new LocationPing(courier, new GeoPoint(2, 2), now.minusSeconds(5))));

    assertThat(repository.findLatest(courier).orElseThrow().point()).isEqualTo(new GeoPoint(1, 1));
  }

  @Test
  void pingsFromUnknownCouriersAreDroppedWithoutBlockingOthers() {
    UUID real = TestDataFactory.courier(jdbc, "Ana", CourierStatus.AVAILABLE);
    UUID ghost = UUID.randomUUID();
    ingestion.ingest(new LocationPing(real, new GeoPoint(1, 1), Instant.now()));
    ingestion.ingest(new LocationPing(ghost, new GeoPoint(1, 1), Instant.now()));

    flusher.flushAndWait();

    assertThat(repository.findLatest(real)).isPresent();
    assertThat(repository.findLatest(ghost)).isEmpty();
    assertThat(buffer.latestSize()).isZero();
  }

  @Test
  void largeBatchIsWrittenInOneFlush() {
    int couriers = 500;
    for (int i = 0; i < couriers; i++) {
      UUID id = TestDataFactory.courier(jdbc, "c" + i, CourierStatus.AVAILABLE);
      ingestion.ingest(new LocationPing(id, new GeoPoint(-12, -77 + i * 0.0001), Instant.now()));
    }

    flusher.flushAndWait();

    assertThat(jdbc.sql("SELECT count(*) FROM courier_location").query(Integer.class).single())
        .isEqualTo(couriers);
  }
}
