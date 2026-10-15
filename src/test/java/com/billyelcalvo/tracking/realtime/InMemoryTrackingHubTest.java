package com.billyelcalvo.tracking.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.location.LocationPing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class InMemoryTrackingHubTest {

  private final InMemoryTrackingHub hub =
      new InMemoryTrackingHub(new ObjectMapper().registerModule(new JavaTimeModule()));
  private final UUID orderId = UUID.randomUUID();
  private final TrackingMessage message =
      TrackingMessage.location(
          orderId, new LocationPing(UUID.randomUUID(), new GeoPoint(-12.0, -77.0), Instant.EPOCH));

  @Test
  void deliversToEverySubscriberOfThatOrderOnly() throws IOException {
    WebSocketSession a = mock(WebSocketSession.class);
    WebSocketSession b = mock(WebSocketSession.class);
    WebSocketSession other = mock(WebSocketSession.class);
    hub.subscribe(orderId, a);
    hub.subscribe(orderId, b);
    hub.subscribe(UUID.randomUUID(), other);

    hub.publish(orderId, message);

    verify(a).sendMessage(any());
    verify(b).sendMessage(any());
    verify(other, never()).sendMessage(any());
    assertThat(hub.subscriberCount()).isEqualTo(3);
  }

  @Test
  void sendsJsonWithTypeAndCoordinates() throws IOException {
    WebSocketSession s = mock(WebSocketSession.class);
    hub.subscribe(orderId, s);

    hub.publish(orderId, message);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
    verify(s).sendMessage(captor.capture());
    String json = ((TextMessage) captor.getValue()).getPayload();
    assertThat(json).contains("\"type\":\"location\"").contains("\"lat\":-12.0");
    assertThat(json).doesNotContain("\"status\""); // nulls are left out
  }

  @Test
  void removesASubscriberWhoseSendFails() throws IOException {
    WebSocketSession broken = mock(WebSocketSession.class);
    doThrow(new IOException("broken pipe")).when(broken).sendMessage(any());
    hub.subscribe(orderId, broken);

    hub.publish(orderId, message);
    hub.publish(orderId, message);

    verify(broken, times(1)).sendMessage(any());
    verify(broken).close(CloseStatus.SESSION_NOT_RELIABLE);
    assertThat(hub.subscriberCount()).isZero();
  }

  @Test
  void unsubscribeAndCompleteKeepTheCountRight() throws IOException {
    WebSocketSession a = mock(WebSocketSession.class);
    WebSocketSession b = mock(WebSocketSession.class);
    hub.subscribe(orderId, a);
    hub.subscribe(orderId, a); // duplicate subscribe is a no-op
    hub.subscribe(orderId, b);

    hub.unsubscribe(orderId, a);
    assertThat(hub.subscriberCount()).isEqualTo(1);

    hub.complete(orderId);
    verify(b).close(CloseStatus.NORMAL);
    assertThat(hub.subscriberCount()).isZero();

    hub.complete(orderId); // nothing left, must not blow up
    hub.publish(orderId, message);
    verify(a, never()).sendMessage(any());
  }
}
