package com.billyelcalvo.tracking.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.billyelcalvo.tracking.common.GeoPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationIngestionServiceTest {

  private final LocationBuffer buffer = new LocationBuffer(1);
  private final CourierLocationRepository repository = mock(CourierLocationRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<LocationPing> received = new ArrayList<>();

  private LocationPing ping(UUID courier) {
    return new LocationPing(courier, new GeoPoint(1, 2), Instant.now());
  }

  @Test
  void aFailingListenerDoesNotStopTheOthersNorLoseThePing() {
    LocationListener broken =
        p -> {
          throw new IllegalStateException("boom");
        };
    var service =
        new LocationIngestionService(buffer, repository, List.of(broken, received::add), meters);
    UUID courier = UUID.randomUUID();

    service.ingest(ping(courier));

    assertThat(received).hasSize(1);
    assertThat(buffer.pendingLatest(courier)).isPresent();
    assertThat(meters.counter("tracking.pings.accepted").count()).isEqualTo(1);
  }

  @Test
  void countsHistoryDropsWhenTheQueueIsFull() {
    var service = new LocationIngestionService(buffer, repository, List.of(), meters);
    UUID courier = UUID.randomUUID();

    service.ingest(ping(courier));
    service.ingest(ping(courier));

    assertThat(meters.counter("tracking.history.dropped").count()).isEqualTo(1);
  }

  @Test
  void latestPrefersTheUnflushedBufferOverTheDatabase() {
    var service = new LocationIngestionService(buffer, repository, List.of(), meters);
    UUID buffered = UUID.randomUUID();
    UUID stored = UUID.randomUUID();
    LocationPing fromDb = ping(stored);
    when(repository.findLatest(stored)).thenReturn(Optional.of(fromDb));
    service.ingest(ping(buffered));

    assertThat(service.latest(buffered)).isPresent();
    verify(repository, never()).findLatest(buffered);
    assertThat(service.latest(stored)).contains(fromDb);
  }
}
