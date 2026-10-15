package com.billyelcalvo.tracking.integration;

import com.billyelcalvo.tracking.TestFixtures;
import com.billyelcalvo.tracking.assignment.ActiveAssignmentRegistry;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.location.LocationBuffer;
import com.billyelcalvo.tracking.location.LocationFlusher;
import com.billyelcalvo.tracking.location.LocationIngestionService;
import com.billyelcalvo.tracking.location.LocationPing;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres + PostGIS in a container, shared by every integration test class (started once,
 * Spring's context cache keeps the app alive between classes). Schedulers are pushed far out so
 * tests decide exactly when a flush or a retry happens.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "tracking.security.api-key=" + TestFixtures.API_KEY,
      "tracking.security.jwt-secret=" + TestFixtures.JWT_SECRET,
      "tracking.ingestion.flush-interval=1h",
      "tracking.ingestion.min-ping-interval=0ms",
      "tracking.assignment.retry-interval=1h"
    })
public abstract class IntegrationTest {

  // Multi-arch build of the official PostGIS image, so it runs natively on ARM laptops and on CI
  static final DockerImageName POSTGIS =
      DockerImageName.parse("imresamu/postgis:16-3.5").asCompatibleSubstituteFor("postgres");

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS);

  static {
    POSTGRES.start();
  }

  @Autowired protected JdbcClient jdbc;
  @Autowired protected LocationIngestionService ingestion;
  @Autowired protected LocationFlusher flusher;
  @Autowired protected LocationBuffer buffer;
  @Autowired protected ActiveAssignmentRegistry registry;

  @BeforeEach
  void cleanDatabase() {
    buffer.drainLatest();
    buffer.drainHistory(Integer.MAX_VALUE);
    jdbc.sql("TRUNCATE delivery_order, courier_location_history, courier_location, courier CASCADE")
        .update();
    registry.reload();
  }

  protected void reportPosition(UUID courierId, GeoPoint point) {
    ingestion.ingest(new LocationPing(courierId, point, Instant.now()));
    flusher.flushAndWait();
  }
}
