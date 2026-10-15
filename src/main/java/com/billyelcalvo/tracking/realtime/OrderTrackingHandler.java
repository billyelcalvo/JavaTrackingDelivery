package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.config.TrackingProperties;
import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.order.OrderService.OrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator.OverflowStrategy;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Customer side. Sessions are wrapped in a ConcurrentWebSocketSessionDecorator because several
 * threads (one per courier ping, plus order events) may write to the same socket at once, and a raw
 * session doesn't allow concurrent sends.
 *
 * <p>With {@code OverflowStrategy.DROP}, a slow client (bad mobile network) loses the oldest queued
 * updates instead of blocking the courier's thread or being disconnected. For a moving dot on a map
 * only the newest position matters anyway.
 */
public class OrderTrackingHandler extends TextWebSocketHandler {

  static final String DECORATED = "decoratedSession";

  private final TrackingHub hub;
  private final OrderService orders;
  private final ObjectMapper mapper;
  private final int sendTimeLimitMs;
  private final int bufferSizeLimit;

  public OrderTrackingHandler(
      TrackingHub hub, OrderService orders, ObjectMapper mapper, TrackingProperties properties) {
    this.hub = hub;
    this.orders = orders;
    this.mapper = mapper;
    this.sendTimeLimitMs = (int) properties.websocket().sendTimeLimit().toMillis();
    this.bufferSizeLimit = properties.websocket().sendBufferSizeLimit();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    UUID orderId = orderId(session);
    WebSocketSession safe =
        new ConcurrentWebSocketSessionDecorator(
            session, sendTimeLimitMs, bufferSizeLimit, OverflowStrategy.DROP);
    session.getAttributes().put(DECORATED, safe);

    // Subscribe first, then send the snapshot: worst case the client sees one update twice,
    // but it can never miss one that happened in between
    hub.subscribe(orderId, safe);

    OrderView view = orders.view(orderId);
    var order = view.order();
    safe.sendMessage(
        json(
            TrackingMessage.status(orderId, order.courierId(), order.status(), order.createdAt())));
    if (view.courierLocation() != null) {
      safe.sendMessage(json(TrackingMessage.location(orderId, view.courierLocation())));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // Read-only channel; anything the client sends is ignored
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    WebSocketSession safe = (WebSocketSession) session.getAttributes().get(DECORATED);
    if (safe != null) {
      hub.unsubscribe(orderId(session), safe);
    }
  }

  private TextMessage json(TrackingMessage message) throws IOException {
    return new TextMessage(mapper.writeValueAsString(message));
  }

  private static UUID orderId(WebSocketSession session) {
    return (UUID) session.getAttributes().get(TrackingHandshakeInterceptor.ORDER_ID);
  }
}
