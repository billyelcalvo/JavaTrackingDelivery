package com.billyelcalvo.tracking.location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-behind buffer between the WebSockets and Postgres.
 *
 * <p>Two structures with different guarantees:
 *
 * <ul>
 *   <li>{@code latest}: one slot per courier, newer pings overwrite older ones. If a courier sends
 *       5 pings between flushes we only write the last one, so the hot table sees at most one
 *       UPDATE per courier per flush no matter how chatty the clients are.
 *   <li>{@code history}: bounded queue of every ping for the trail. When it's full we drop and
 *       count, because losing a breadcrumb is better than running out of heap.
 * </ul>
 */
public class LocationBuffer {

  private final ConcurrentHashMap<UUID, LocationPing> latest = new ConcurrentHashMap<>();
  private final BlockingQueue<LocationPing> history;

  public LocationBuffer(int historyCapacity) {
    this.history = new ArrayBlockingQueue<>(historyCapacity);
  }

  /**
   * @return false if the history queue was full and the ping didn't make it into the trail. The
   *     latest position is always kept.
   */
  public boolean add(LocationPing ping) {
    keepNewest(ping);
    return history.offer(ping);
  }

  public Optional<LocationPing> pendingLatest(UUID courierId) {
    return Optional.ofNullable(latest.get(courierId));
  }

  /** Takes a snapshot of the latest positions and removes exactly what was taken. */
  public List<LocationPing> drainLatest() {
    List<LocationPing> drained = new ArrayList<>(latest.size());
    for (Map.Entry<UUID, LocationPing> entry : latest.entrySet()) {
      // remove(key, value) only succeeds if nobody replaced it meanwhile; if a newer ping
      // arrived we leave it for the next flush instead of losing it
      if (latest.remove(entry.getKey(), entry.getValue())) {
        drained.add(entry.getValue());
      }
    }
    return drained;
  }

  public List<LocationPing> drainHistory(int max) {
    List<LocationPing> drained = new ArrayList<>(Math.min(max, history.size()));
    history.drainTo(drained, max);
    return drained;
  }

  /** Puts back positions whose write failed, unless a newer one already arrived. */
  public void requeueLatest(List<LocationPing> pings) {
    pings.forEach(this::keepNewest);
  }

  public int requeueHistory(List<LocationPing> pings) {
    int dropped = 0;
    for (LocationPing ping : pings) {
      if (!history.offer(ping)) {
        dropped++;
      }
    }
    return dropped;
  }

  public int latestSize() {
    return latest.size();
  }

  public int historySize() {
    return history.size();
  }

  private void keepNewest(LocationPing ping) {
    latest.merge(
        ping.courierId(),
        ping,
        (current, incoming) -> incoming.isNewerThan(current) ? incoming : current);
  }
}
