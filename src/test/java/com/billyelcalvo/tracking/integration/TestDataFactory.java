package com.billyelcalvo.tracking.integration;

import com.billyelcalvo.tracking.courier.CourierStatus;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts rows straight into the DB when a test only cares about the state, not the API. */
final class TestDataFactory {

  private TestDataFactory() {}

  static UUID courier(JdbcClient jdbc, String name, CourierStatus status) {
    return jdbc.sql("INSERT INTO courier (name, status) VALUES (:n, :s) RETURNING id")
        .param("n", name)
        .param("s", status.name())
        .query(UUID.class)
        .single();
  }

  static void ageLocation(JdbcClient jdbc, UUID courierId, int seconds) {
    jdbc.sql(
            "UPDATE courier_location SET updated_at = now() - make_interval(secs => :s)"
                + " WHERE courier_id = :id")
        .param("s", (double) seconds)
        .param("id", courierId)
        .update();
  }
}
