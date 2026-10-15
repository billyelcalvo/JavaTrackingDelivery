package com.billyelcalvo.tracking.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class InMemoryTrackingHub implements TrackingHub {

  private static final Logger log = LoggerFactory.getLogger(InMemoryTrackingHub.class);

  private final ConcurrentHashMap<UUID, Set<WebSocketSession>> subscribers =
      new ConcurrentHashMap<>();
  private final AtomicInteger sessions = new AtomicInteger();
  private final ObjectMapper mapper;

  public InMemoryTrackingHub(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void subscribe(UUID orderId, WebSocketSession session) {
    if (subscribers.computeIfAbsent(orderId, k -> ConcurrentHashMap.newKeySet()).add(session)) {
      sessions.incrementAndGet();
    }
  }

  @Override
  public void unsubscribe(UUID orderId, WebSocketSession session) {
    subscribers.computeIfPresent(
        orderId,
        (k, set) -> {
          if (set.remove(session)) {
            sessions.decrementAndGet();
          }
          // Drop the empty set so finished orders don't pile up in memory
          return set.isEmpty() ? null : set;
        });
  }

  @Override
  public void publish(UUID orderId, TrackingMessage message) {
    Set<WebSocketSession> targets = subscribers.get(orderId);
    if (targets == null || targets.isEmpty()) {
      return;
    }
    // Serialize once, no matter how many people watch the same order
    TextMessage payload = new TextMessage(toJson(message));
    for (WebSocketSession session : targets) {
      try {
        session.sendMessage(payload);
      } catch (IOException | RuntimeException ex) {
        log.atDebug()
            .addKeyValue("orderId", orderId)
            .addKeyValue("sessionId", session.getId())
            .log("Dropping tracking subscriber");
        unsubscribe(orderId, session);
        closeQuietly(session, CloseStatus.SESSION_NOT_RELIABLE);
      }
    }
  }

  @Override
  public void complete(UUID orderId) {
    Set<WebSocketSession> targets = subscribers.remove(orderId);
    if (targets == null) {
      return;
    }
    sessions.addAndGet(-targets.size());
    targets.forEach(s -> closeQuietly(s, CloseStatus.NORMAL));
  }

  @Override
  public int subscriberCount() {
    return sessions.get();
  }

  private String toJson(TrackingMessage message) {
    try {
      return mapper.writeValueAsString(message);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("TrackingMessage is not serializable", ex);
    }
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      session.close(status);
    } catch (IOException | RuntimeException ignored) {
      // Already gone, nothing else to do
    }
  }
}
