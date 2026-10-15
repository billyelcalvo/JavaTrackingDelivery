package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.config.TrackingProperties;
import com.billyelcalvo.tracking.location.LocationIngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Receives courier pings. The courier id comes from the JWT checked during the handshake, never
 * from the message body, so a courier can't report someone else's position.
 */
public class CourierLocationHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CourierLocationHandler.class);
  private static final String LAST_ACCEPTED = "lastAcceptedAt";

  private final LocationIngestionService ingestion;
  private final PingValidator validator;
  private final ObjectMapper mapper;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Duration minInterval;
  private final AtomicInteger connected = new AtomicInteger();

  public CourierLocationHandler(
      LocationIngestionService ingestion,
      PingValidator validator,
      ObjectMapper mapper,
      MeterRegistry meters,
      Clock clock,
      TrackingProperties properties) {
    this.ingestion = ingestion;
    this.validator = validator;
    this.mapper = mapper;
    this.meters = meters;
    this.clock = clock;
    this.minInterval = properties.ingestion().minPingInterval();
    Gauge.builder("tracking.ws.sessions", connected, AtomicInteger::get)
        .tag("type", "courier")
        .register(meters);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    connected.incrementAndGet();
    log.atDebug().addKeyValue("courierId", courierId(session)).log("Courier connected");
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    UUID courierId = courierId(session);
    Instant now = clock.instant();

    // Throttle per connection: a misbehaving client sending every 10 ms shouldn't cost us
    // more than one sending every few seconds
    Instant last = (Instant) session.getAttributes().get(LAST_ACCEPTED);
    if (last != null && Duration.between(last, now).compareTo(minInterval) < 0) {
      reject(session, "too_frequent");
      return;
    }

    CourierPingMessage ping;
    try {
      ping = mapper.readValue(message.getPayload(), CourierPingMessage.class);
    } catch (JsonProcessingException ex) {
      reject(session, "invalid_json");
      return;
    }

    PingValidator.Result result = validator.validate(courierId, ping);
    if (!result.isAccepted()) {
      reject(session, result.rejectionReason());
      return;
    }
    session.getAttributes().put(LAST_ACCEPTED, now);
    ingestion.ingest(result.ping());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    connected.decrementAndGet();
    log.atDebug()
        .addKeyValue("courierId", courierId(session))
        .addKeyValue("code", status.getCode())
        .log("Courier disconnected");
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.atDebug()
        .addKeyValue("courierId", courierId(session))
        .setCause(exception)
        .log("Courier socket error");
  }

  private void reject(WebSocketSession session, String reason) throws IOException {
    meters.counter("tracking.pings.rejected", "reason", reason).increment();
    // Tell the client why, so a buggy app can be fixed instead of silently losing data
    session.sendMessage(new TextMessage("{\"type\":\"error\",\"reason\":\"" + reason + "\"}"));
  }

  private static UUID courierId(WebSocketSession session) {
    return (UUID) session.getAttributes().get(CourierHandshakeInterceptor.COURIER_ID);
  }
}
