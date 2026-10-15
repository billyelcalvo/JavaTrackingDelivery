package com.billyelcalvo.tracking.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.billyelcalvo.tracking.TestFixtures;
import com.billyelcalvo.tracking.common.GeoPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class LocationFlusherTest {

  private final LocationBuffer buffer = new LocationBuffer(100);
  private final CourierLocationRepository repository = mock(CourierLocationRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final LocationFlusher flusher =
      new LocationFlusher(
          buffer, repository, new ThreadPoolTaskScheduler(), TestFixtures.properties(), meters);

  private LocationPing ping(UUID courier) {
    return new LocationPing(courier, new GeoPoint(1, 2), Instant.now());
  }

  @Test
  void writesLatestAndHistoryInBatches() {
    for (int i = 0; i < 90; i++) {
      buffer.add(ping(UUID.randomUUID()));
    }

    flusher.flush();

    verify(repository).upsertLatest(argThat(l -> l.size() == 90));
    // max batch size in the fixture is 50 -> 50 + 40
    verify(repository, times(2)).insertHistory(anyList());
    assertThat(buffer.latestSize()).isZero();
    assertThat(buffer.historySize()).isZero();
  }

  @Test
  void keepsEverythingForTheNextRoundWhenTheDatabaseIsDown() {
    UUID courier = UUID.randomUUID();
    buffer.add(ping(courier));
    doThrow(new QueryTimeoutException("db down")).when(repository).upsertLatest(anyList());

    flusher.flush();

    assertThat(buffer.pendingLatest(courier)).isPresent();
    assertThat(buffer.historySize()).isEqualTo(1);
    assertThat(meters.counter("tracking.flush.failures").count()).isEqualTo(1);
  }

  @Test
  void requeuesHistoryWhenItsInsertFails() {
    buffer.add(ping(UUID.randomUUID()));
    doThrow(new QueryTimeoutException("db down")).when(repository).insertHistory(anyList());

    flusher.flush();

    assertThat(buffer.historySize()).isEqualTo(1);
  }

  @Test
  void aPoisonRowDoesNotBlockTheRestOfTheBatch() {
    UUID good = UUID.randomUUID();
    UUID ghost = UUID.randomUUID();
    buffer.add(ping(good));
    buffer.add(ping(ghost));
    doThrow(new DataIntegrityViolationException("fk"))
        .when(repository)
        .upsertLatest(argThat(l -> l.size() == 2));
    doThrow(new DataIntegrityViolationException("fk"))
        .when(repository)
        .upsertLatest(argThat(l -> l.size() == 1 && l.get(0).courierId().equals(ghost)));

    flusher.flush();

    verify(repository)
        .upsertLatest(argThat(l -> l.size() == 1 && l.get(0).courierId().equals(good)));
    // The ghost is dropped, not requeued forever
    assertThat(buffer.latestSize()).isZero();
  }

  @Test
  void lifecycleRunsAFinalFlushOnStop() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    var lifecycle =
        new LocationFlusher(buffer, repository, scheduler, TestFixtures.properties(), meters);
    lifecycle.start();
    assertThat(lifecycle.isRunning()).isTrue();
    buffer.add(ping(UUID.randomUUID()));

    lifecycle.stop();

    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(buffer.latestSize()).isZero();
    scheduler.shutdown();
  }

  @Test
  void emptyFlushStillWorks() {
    flusher.flush();
    verify(repository).upsertLatest(List.of());
  }
}
