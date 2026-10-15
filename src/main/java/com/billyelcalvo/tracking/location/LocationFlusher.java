package com.billyelcalvo.tracking.location;

import com.billyelcalvo.tracking.config.TrackingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Periodically moves the buffer into Postgres with batched statements. It's a SmartLifecycle so it
 * starts after the context is ready and, on shutdown, runs one last flush before the datasource
 * goes away.
 */
@Component
public class LocationFlusher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(LocationFlusher.class);

  private final LocationBuffer buffer;
  private final CourierLocationRepository repository;
  private final TaskScheduler scheduler;
  private final TrackingProperties.Ingestion config;
  private final ReentrantLock flushLock = new ReentrantLock();
  private final Timer flushTimer;
  private final Counter written;
  private final Counter failures;
  private final Counter dropped;
  private volatile ScheduledFuture<?> task;

  public LocationFlusher(
      LocationBuffer buffer,
      CourierLocationRepository repository,
      TaskScheduler scheduler,
      TrackingProperties properties,
      MeterRegistry meters) {
    this.buffer = buffer;
    this.repository = repository;
    this.scheduler = scheduler;
    this.config = properties.ingestion();
    this.flushTimer = meters.timer("tracking.flush.duration");
    this.written = meters.counter("tracking.flush.rows", "table", "courier_location");
    this.failures = meters.counter("tracking.flush.failures");
    this.dropped = meters.counter("tracking.history.dropped");
  }

  /**
   * Scheduled path. Only one flush runs at a time; if one is already running we skip, since it will
   * pick up whatever is in the buffer anyway.
   */
  public void flush() {
    if (!flushLock.tryLock()) {
      return;
    }
    try {
      flushTimer.record(this::doFlush);
    } finally {
      flushLock.unlock();
    }
  }

  /**
   * Waits for any running flush and then flushes again. Used on shutdown, where skipping would lose
   * whatever arrived after the running flush took its snapshot.
   */
  public void flushAndWait() {
    flushLock.lock();
    try {
      flushTimer.record(this::doFlush);
    } finally {
      flushLock.unlock();
    }
  }

  private void doFlush() {
    List<LocationPing> latest = buffer.drainLatest();
    try {
      writeLatest(latest);
      written.increment(latest.size());
    } catch (DataAccessException ex) {
      // Most likely the database is down or slow. Keep the positions for the next round.
      failures.increment();
      buffer.requeueLatest(latest);
      log.atError()
          .addKeyValue("count", latest.size())
          .setCause(ex)
          .log("Flush of latest positions failed, requeued");
      return;
    }

    List<LocationPing> history;
    while (!(history = buffer.drainHistory(config.maxBatchSize())).isEmpty()) {
      try {
        repository.insertHistory(history);
      } catch (DataAccessException ex) {
        failures.increment();
        int lost = buffer.requeueHistory(history);
        dropped.increment(lost);
        log.atError()
            .addKeyValue("requeued", history.size() - lost)
            .addKeyValue("dropped", lost)
            .setCause(ex)
            .log("Flush of history failed");
        return;
      }
    }
  }

  private void writeLatest(List<LocationPing> latest) {
    try {
      repository.upsertLatest(latest);
    } catch (DataIntegrityViolationException ex) {
      // One bad row (e.g. a courier deleted while still connected) fails the whole batch.
      // Retry row by row so a single poison ping can't block everyone else forever.
      log.atWarn()
          .addKeyValue("size", latest.size())
          .log("Batch upsert rejected, retrying row by row");
      for (LocationPing ping : latest) {
        try {
          repository.upsertLatest(List.of(ping));
        } catch (DataIntegrityViolationException rowEx) {
          log.atWarn()
              .addKeyValue("courierId", ping.courierId())
              .log("Dropping location for unknown courier");
        }
      }
    }
  }

  @Override
  public void start() {
    task = scheduler.scheduleWithFixedDelay(this::flushSafely, config.flushInterval());
    log.atInfo().addKeyValue("interval", config.flushInterval()).log("Location flusher started");
  }

  @Override
  public void stop() {
    if (task != null) {
      task.cancel(false);
      task = null;
    }
    flushAndWait();
    log.info("Location flusher stopped after final flush");
  }

  @Override
  public boolean isRunning() {
    return task != null;
  }

  @Override
  public int getPhase() {
    // Lifecycles stop from highest phase to lowest. Sitting below the web server means we stop
    // after it has closed every WebSocket (no more pings), but before the datasource is destroyed.
    return SmartLifecycle.DEFAULT_PHASE - 4096;
  }

  private void flushSafely() {
    try {
      flush();
    } catch (RuntimeException ex) {
      // Swallow so the scheduler keeps running; scheduled tasks die on uncaught exceptions
      log.error("Unexpected error in location flusher", ex);
    }
  }
}
