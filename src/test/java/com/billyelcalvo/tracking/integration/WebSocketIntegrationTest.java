package com.billyelcalvo.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.courier.CourierStatus;
import com.billyelcalvo.tracking.location.CourierLocationRepository;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.security.CourierTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Real sockets against the running server: courier sends, customer receives. */
class WebSocketIntegrationTest extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired CourierTokenService tokens;
  @Autowired OrderService orders;
  @Autowired CourierLocationRepository locations;
  @Autowired ObjectMapper mapper;

  private final StandardWebSocketClient client = new StandardWebSocketClient();

  private WebSocketSession connect(String path, BlockingQueue<String> inbox) throws Exception {
    return client
        .execute(
            new TextWebSocketHandler() {
              @Override
              protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                inbox.add(m.getPayload());
              }
            },
            new WebSocketHttpHeaders(),
            URI.create("ws://localhost:" + port + path))
        .get(5, TimeUnit.SECONDS);
  }

  private JsonNode next(BlockingQueue<String> inbox) throws Exception {
    String raw = inbox.poll(5, TimeUnit.SECONDS);
    assertThat(raw).as("expected a message").isNotNull();
    return mapper.readTree(raw);
  }

  @Test
  void customerSeesTheCourierMoveLive() throws Exception {
    UUID courier = TestDataFactory.courier(jdbc, "Ana", CourierStatus.AVAILABLE);
    reportPosition(courier, new GeoPoint(-12.1200, -77.0297));
    DeliveryOrder order =
        orders.create(new GeoPoint(-12.1211, -77.0297), new GeoPoint(-12.13, -77.02));
    assertThat(order.courierId()).isEqualTo(courier);

    BlockingQueue<String> customerInbox = new LinkedBlockingQueue<>();
    WebSocketSession customer = connect("/ws/tracking?orderId=" + order.id(), customerInbox);

    // On connect: a snapshot with status and last known position
    assertThat(next(customerInbox).get("status").asText()).isEqualTo("ASSIGNED");
    assertThat(next(customerInbox).get("type").asText()).isEqualTo("location");

    BlockingQueue<String> courierInbox = new LinkedBlockingQueue<>();
    WebSocketSession courierSocket =
        connect("/ws/couriers?token=" + tokens.issue(courier).token(), courierInbox);
    courierSocket.sendMessage(new TextMessage("{\"lat\":-12.1205,\"lng\":-77.0297}"));

    JsonNode live = next(customerInbox);
    assertThat(live.get("type").asText()).isEqualTo("location");
    assertThat(live.get("lat").asDouble()).isEqualTo(-12.1205);

    // Bad input gets an explicit error back to the courier, the socket stays open
    courierSocket.sendMessage(new TextMessage("{\"lat\":500,\"lng\":0}"));
    assertThat(next(courierInbox).get("reason").asText()).isEqualTo("out_of_range");
    assertThat(courierSocket.isOpen()).isTrue();

    // The ping also lands in Postgres once the flusher runs
    flusher.flushAndWait();
    assertThat(locations.findLatest(courier).orElseThrow().point().lat()).isEqualTo(-12.1205);

    // Status changes are pushed too, and the stream closes when the order is done
    orders.markPickedUp(courier, order.id());
    assertThat(next(customerInbox).get("status").asText()).isEqualTo("PICKED_UP");
    orders.markDelivered(courier, order.id());
    assertThat(next(customerInbox).get("status").asText()).isEqualTo("DELIVERED");
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !customer.isOpen());

    courierSocket.close();
  }

  @Test
  void courierAuthCanAlsoGoInTheAuthorizationHeader() throws Exception {
    UUID courier = TestDataFactory.courier(jdbc, "Ana", CourierStatus.AVAILABLE);
    var headers = new WebSocketHttpHeaders();
    headers.setBearerAuth(tokens.issue(courier).token());

    WebSocketSession socket =
        client
            .execute(
                new TextWebSocketHandler(),
                headers,
                URI.create("ws://localhost:" + port + "/ws/couriers"))
            .get(5, TimeUnit.SECONDS);
    socket.sendMessage(new TextMessage("{\"lat\":1,\"lng\":1}"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> buffer.pendingLatest(courier).isPresent());
    socket.close();
  }

  @Test
  void handshakeIsRejectedWithoutValidCredentials() {
    assertThatThrownBy(() -> connect("/ws/couriers?token=forged", new LinkedBlockingQueue<>()))
        .hasMessageContaining("401");
    assertThatThrownBy(() -> connect("/ws/couriers", new LinkedBlockingQueue<>()))
        .hasMessageContaining("401");
    assertThatThrownBy(
            () -> connect("/ws/tracking?orderId=" + UUID.randomUUID(), new LinkedBlockingQueue<>()))
        .hasMessageContaining("404");
    assertThatThrownBy(() -> connect("/ws/tracking?orderId=nope", new LinkedBlockingQueue<>()))
        .hasMessageContaining("400");
  }
}
