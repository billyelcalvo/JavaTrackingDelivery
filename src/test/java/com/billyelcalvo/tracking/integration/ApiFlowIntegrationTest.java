package com.billyelcalvo.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.TestFixtures;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Drives the whole delivery through HTTP, the way the two client apps would. */
class ApiFlowIntegrationTest extends IntegrationTest {

  @Autowired TestRestTemplate http;

  private HttpHeaders backOffice() {
    HttpHeaders h = new HttpHeaders();
    h.set("X-API-Key", TestFixtures.API_KEY);
    return h;
  }

  private HttpHeaders courier(String token) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    return h;
  }

  private ResponseEntity<JsonNode> call(
      HttpMethod method, String path, HttpHeaders h, Object body) {
    return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
  }

  private JsonNode registerCourier(String name) {
    var res = call(HttpMethod.POST, "/api/couriers", backOffice(), Map.of("name", name));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private static Map<String, Object> orderBody() {
    return Map.of(
        "pickup", Map.of("lat", -12.1211, "lng", -77.0297),
        "dropoff", Map.of("lat", -12.13, "lng", -77.02));
  }

  @Test
  void fullDeliveryLifecycle() {
    JsonNode ana = registerCourier("Ana");
    String token = ana.get("token").asText();
    UUID anaId = UUID.fromString(ana.get("id").asText());

    var available =
        call(HttpMethod.PUT, "/api/me/status", courier(token), Map.of("status", "AVAILABLE"));
    assertThat(available.getBody().get("status").asText()).isEqualTo("AVAILABLE");
    reportPosition(anaId, new GeoPoint(-12.1200, -77.0297));

    var created = call(HttpMethod.POST, "/api/orders", backOffice(), orderBody());
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().get("status").asText()).isEqualTo("ASSIGNED");
    assertThat(created.getBody().get("courierId").asText()).isEqualTo(anaId.toString());
    String orderId = created.getBody().get("id").asText();

    var current = call(HttpMethod.GET, "/api/me/orders/current", courier(token), null);
    assertThat(current.getBody().get("id").asText()).isEqualTo(orderId);

    var tracked = call(HttpMethod.GET, "/api/orders/" + orderId, backOffice(), null);
    assertThat(tracked.getBody().get("courierPosition").get("lat").asDouble()).isEqualTo(-12.12);

    // Going offline while carrying an order is not allowed
    var offline =
        call(HttpMethod.PUT, "/api/me/status", courier(token), Map.of("status", "OFFLINE"));
    assertThat(offline.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    var picked =
        call(HttpMethod.POST, "/api/me/orders/" + orderId + "/pickup", courier(token), null);
    assertThat(picked.getBody().get("status").asText()).isEqualTo("PICKED_UP");

    var delivered =
        call(HttpMethod.POST, "/api/me/orders/" + orderId + "/deliver", courier(token), null);
    assertThat(delivered.getBody().get("status").asText()).isEqualTo("DELIVERED");
    assertThat(delivered.getBody().get("deliveredAt")).isNotNull();

    var me = call(HttpMethod.GET, "/api/me", courier(token), null);
    assertThat(me.getBody().get("status").asText()).isEqualTo("AVAILABLE");
    assertThat(call(HttpMethod.GET, "/api/me/orders/current", courier(token), null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void aCourierCannotTouchSomeoneElsesOrder() {
    JsonNode ana = registerCourier("Ana");
    JsonNode bob = registerCourier("Bob");
    UUID anaId = UUID.fromString(ana.get("id").asText());
    call(
        HttpMethod.PUT,
        "/api/me/status",
        courier(ana.get("token").asText()),
        Map.of("status", "AVAILABLE"));
    reportPosition(anaId, new GeoPoint(-12.1200, -77.0297));
    String orderId =
        call(HttpMethod.POST, "/api/orders", backOffice(), orderBody())
            .getBody()
            .get("id")
            .asText();

    var res =
        call(
            HttpMethod.POST,
            "/api/me/orders/" + orderId + "/pickup",
            courier(bob.get("token").asText()),
            null);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void cancellingAnAssignedOrderFreesTheCourier() {
    JsonNode ana = registerCourier("Ana");
    String token = ana.get("token").asText();
    call(HttpMethod.PUT, "/api/me/status", courier(token), Map.of("status", "AVAILABLE"));
    reportPosition(UUID.fromString(ana.get("id").asText()), new GeoPoint(-12.1200, -77.0297));
    String orderId =
        call(HttpMethod.POST, "/api/orders", backOffice(), orderBody())
            .getBody()
            .get("id")
            .asText();

    var cancelled = call(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", backOffice(), null);
    assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
    assertThat(
            call(HttpMethod.GET, "/api/me", courier(token), null).getBody().get("status").asText())
        .isEqualTo("AVAILABLE");

    // Cancelling twice is a state conflict, not a server error
    assertThat(
            call(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", backOffice(), null)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void nearbyListsCouriersByDistance() {
    JsonNode near = registerCourier("near");
    JsonNode far = registerCourier("far");
    reportPosition(UUID.fromString(far.get("id").asText()), new GeoPoint(-12.1300, -77.0297));
    reportPosition(UUID.fromString(near.get("id").asText()), new GeoPoint(-12.1215, -77.0297));

    var res =
        call(
            HttpMethod.GET,
            "/api/couriers/nearby?lat=-12.1211&lng=-77.0297&radiusMeters=3000",
            backOffice(),
            null);

    assertThat(res.getBody()).hasSize(2);
    assertThat(res.getBody().get(0).get("name").asText()).isEqualTo("near");
    assertThat(res.getBody().get(0).get("distanceMeters").asLong()).isBetween(40L, 50L);
  }

  @Test
  void rejectsBadInputAndMissingCredentials() {
    var noKey = call(HttpMethod.POST, "/api/orders", new HttpHeaders(), orderBody());
    assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    var badCoords =
        call(
            HttpMethod.POST,
            "/api/orders",
            backOffice(),
            Map.of("pickup", Map.of("lat", 123, "lng", 0), "dropoff", Map.of("lat", 0, "lng", 0)));
    assertThat(badCoords.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(badCoords.getBody().get("detail").asText()).contains("pickup.lat");

    var blankName = call(HttpMethod.POST, "/api/couriers", backOffice(), Map.of("name", " "));
    assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var notFound = call(HttpMethod.GET, "/api/orders/" + UUID.randomUUID(), backOffice(), null);
    assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    var badUuid = call(HttpMethod.GET, "/api/orders/not-a-uuid", backOffice(), null);
    assertThat(badUuid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var badRadius =
        call(HttpMethod.GET, "/api/couriers/nearby?lat=0&lng=0&radiusMeters=0", backOffice(), null);
    assertThat(badRadius.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var busy =
        call(
            HttpMethod.PUT,
            "/api/me/status",
            courier(registerCourier("x").get("token").asText()),
            Map.of("status", "BUSY"));
    assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void operationalEndpointsAreUp() {
    assertThat(
            http.getForEntity("/actuator/health", JsonNode.class).getBody().get("status").asText())
        .isEqualTo("UP");
    assertThat(
            http.getForEntity("/v3/api-docs", JsonNode.class)
                .getBody()
                .get("paths")
                .has("/api/orders"))
        .isTrue();
    // Prometheus export is off in tests by Boot's design; the meter itself must exist
    assertThat(
            http.getForEntity("/actuator/metrics/tracking.pings.accepted", JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
