package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.order.OrderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** Rejects the upgrade early when the order id is missing, malformed or unknown. */
public class TrackingHandshakeInterceptor implements HandshakeInterceptor {

  public static final String ORDER_ID = "orderId";

  private final OrderRepository orders;

  public TrackingHandshakeInterceptor(OrderRepository orders) {
    this.orders = orders;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String raw =
        UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(ORDER_ID);
    UUID orderId;
    try {
      orderId = UUID.fromString(raw);
    } catch (IllegalArgumentException | NullPointerException ex) {
      response.setStatusCode(HttpStatus.BAD_REQUEST);
      return false;
    }
    if (!orders.exists(orderId)) {
      response.setStatusCode(HttpStatus.NOT_FOUND);
      return false;
    }
    attributes.put(ORDER_ID, orderId);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}
}
