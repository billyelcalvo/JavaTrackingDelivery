package com.billyelcalvo.tracking.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.billyelcalvo.tracking.TestFixtures;
import com.billyelcalvo.tracking.location.LocationIngestionService;
import com.billyelcalvo.tracking.location.LocationPing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class CourierLocationHandlerTest {

  private final LocationIngestionService ingestion = mock(LocationIngestionService.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
  private final WebSocketSession session = mock(WebSocketSession.class);
  private final Map<String, Object> attributes = new HashMap<>();
  private final UUID courier = UUID.randomUUID();
  private CourierLocationHandler handler;

  @BeforeEach
  void setUp() {
    attributes.put(CourierHandshakeInterceptor.COURIER_ID, courier);
    when(session.getAttributes()).thenReturn(attributes);
    handler =
        new CourierLocationHandler(
            ingestion,
            new PingValidator(clock),
            new ObjectMapper(),
            meters,
            clock,
            TestFixtures.properties());
  }

  @Test
  void ingestsAValidPingUnderTheAuthenticatedCourier() throws Exception {
    handler.handleTextMessage(session, new TextMessage("{\"lat\":-12.05,\"lng\":-77.04}"));

    ArgumentCaptor<LocationPing> captor = ArgumentCaptor.forClass(LocationPing.class);
    verify(ingestion).ingest(captor.capture());
    assertThat(captor.getValue().courierId()).isEqualTo(courier);
  }

  @Test
  void throttlesPingsThatComeTooFast() throws Exception {
    handler.handleTextMessage(session, new TextMessage("{\"lat\":1,\"lng\":1}"));
    clock.advanceMillis(100); // min interval in fixture is 500 ms
    handler.handleTextMessage(session, new TextMessage("{\"lat\":1,\"lng\":1}"));
    clock.advanceMillis(500);
    handler.handleTextMessage(session, new TextMessage("{\"lat\":1,\"lng\":1}"));

    verify(ingestion, org.mockito.Mockito.times(2)).ingest(any());
    assertThat(meters.counter("tracking.pings.rejected", "reason", "too_frequent").count())
        .isEqualTo(1);
  }

  @Test
  void answersWithAnErrorOnBadJson() throws Exception {
    handler.handleTextMessage(session, new TextMessage("{not json"));

    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(captor.capture());
    assertThat(captor.getValue().getPayload()).contains("invalid_json");
    verify(ingestion, never()).ingest(any());
  }

  @Test
  void answersWithAnErrorOnInvalidCoordinates() throws Exception {
    handler.handleTextMessage(session, new TextMessage("{\"lat\":200,\"lng\":1}"));

    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(captor.capture());
    assertThat(captor.getValue().getPayload()).contains("out_of_range");
  }

  @Test
  void tracksConnectedSessions() {
    handler.afterConnectionEstablished(session);
    assertThat(meters.get("tracking.ws.sessions").tag("type", "courier").gauge().value())
        .isEqualTo(1);

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    assertThat(meters.get("tracking.ws.sessions").tag("type", "courier").gauge().value()).isZero();
  }

  /** Lets tests move time forward without sleeping. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advanceMillis(long ms) {
      now = now.plusMillis(ms);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
