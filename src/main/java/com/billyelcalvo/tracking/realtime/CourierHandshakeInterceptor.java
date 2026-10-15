package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.security.CourierTokenService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authenticates the courier before the upgrade, so unauthenticated clients never get a socket.
 * Accepts {@code Authorization: Bearer} (native apps) or {@code ?token=} (browsers can't set
 * headers on a WebSocket).
 */
public class CourierHandshakeInterceptor implements HandshakeInterceptor {

  public static final String COURIER_ID = "courierId";

  private final CourierTokenService tokens;

  public CourierHandshakeInterceptor(CourierTokenService tokens) {
    this.tokens = tokens;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    Optional<UUID> courierId = tokens.verify(extractToken(request));
    if (courierId.isEmpty()) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    attributes.put(COURIER_ID, courierId.get());
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}

  private static String extractToken(ServerHttpRequest request) {
    String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return header.substring(7).trim();
    }
    return UriComponentsBuilder.fromUri(request.getURI())
        .build()
        .getQueryParams()
        .getFirst("token");
  }
}
