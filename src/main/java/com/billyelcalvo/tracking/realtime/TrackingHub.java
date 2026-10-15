package com.billyelcalvo.tracking.realtime;

import java.util.UUID;
import org.springframework.web.socket.WebSocketSession;

/**
 * Publish/subscribe of tracking messages by order id. The in-memory version is enough for one
 * instance; running several would mean swapping this for a Redis pub/sub (or similar) backed
 * implementation without touching the handlers.
 */
public interface TrackingHub {

  void subscribe(UUID orderId, WebSocketSession session);

  void unsubscribe(UUID orderId, WebSocketSession session);

  void publish(UUID orderId, TrackingMessage message);

  /** Closes every subscriber of a finished order. */
  void complete(UUID orderId);

  int subscriberCount();
}
