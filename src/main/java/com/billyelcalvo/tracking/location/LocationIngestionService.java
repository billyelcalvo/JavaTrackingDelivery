package com.billyelcalvo.tracking.location;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point for every courier ping. It does the minimum on the hot path: buffer the ping and
 * notify listeners. No database call happens here, which is what lets a single instance take
 * thousands of pings per second.
 */
@Service
public class LocationIngestionService {

  private static final Logger log = LoggerFactory.getLogger(LocationIngestionService.class);

  private final LocationBuffer buffer;
  private final CourierLocationRepository repository;
  private final List<LocationListener> listeners;
  private final Counter accepted;
  private final Counter historyDropped;

  public LocationIngestionService(
      LocationBuffer buffer,
      CourierLocationRepository repository,
      List<LocationListener> listeners,
      MeterRegistry meters) {
    this.buffer = buffer;
    this.repository = repository;
    this.listeners = List.copyOf(listeners);
    this.accepted = meters.counter("tracking.pings.accepted");
    this.historyDropped = meters.counter("tracking.history.dropped");
    Gauge.builder("tracking.buffer.latest", buffer, LocationBuffer::latestSize).register(meters);
    Gauge.builder("tracking.buffer.history", buffer, LocationBuffer::historySize).register(meters);
  }

  public void ingest(LocationPing ping) {
    if (!buffer.add(ping)) {
      historyDropped.increment();
    }
    accepted.increment();
    for (LocationListener listener : listeners) {
      try {
        listener.onLocation(ping);
      } catch (RuntimeException ex) {
        // A broken subscriber must never make us lose the ping itself
        log.atWarn()
            .addKeyValue("listener", listener.getClass().getSimpleName())
            .setCause(ex)
            .log("Location listener failed");
      }
    }
  }

  /** Freshest known position: the unflushed buffer wins over the database. */
  public Optional<LocationPing> latest(UUID courierId) {
    return buffer.pendingLatest(courierId).or(() -> repository.findLatest(courierId));
  }
}
