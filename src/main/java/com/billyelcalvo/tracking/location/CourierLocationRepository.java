package com.billyelcalvo.tracking.location;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CourierLocationRepository {

  // The WHERE on the conflict branch makes the upsert idempotent and order-safe: a late,
  // older ping can never overwrite a newer position.
  private static final String UPSERT_LATEST =
      """
      INSERT INTO courier_location (courier_id, location, recorded_at, updated_at)
      VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, now())
      ON CONFLICT (courier_id) DO UPDATE
         SET location = EXCLUDED.location,
             recorded_at = EXCLUDED.recorded_at,
             updated_at = now()
       WHERE courier_location.recorded_at < EXCLUDED.recorded_at
      """;

  private static final String INSERT_HISTORY =
      """
      INSERT INTO courier_location_history (courier_id, location, recorded_at)
      VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
      """;

  private final JdbcTemplate jdbcTemplate;
  private final JdbcClient jdbc;

  public CourierLocationRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
    this.jdbcTemplate = jdbcTemplate;
    this.jdbc = jdbc;
  }

  public void upsertLatest(List<LocationPing> pings) {
    if (pings.isEmpty()) {
      return;
    }
    // Same lock order on every flush, so two instances flushing overlapping couriers can't
    // deadlock each other
    List<LocationPing> sorted =
        pings.stream().sorted(Comparator.comparing(LocationPing::courierId)).toList();
    jdbcTemplate.batchUpdate(UPSERT_LATEST, sorted, sorted.size(), this::bind);
  }

  public void insertHistory(List<LocationPing> pings) {
    if (pings.isEmpty()) {
      return;
    }
    jdbcTemplate.batchUpdate(INSERT_HISTORY, pings, pings.size(), this::bind);
  }

  public Optional<LocationPing> findLatest(UUID courierId) {
    return jdbc.sql(
            """
            SELECT courier_id, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng,
                   recorded_at
            FROM courier_location WHERE courier_id = :id
            """)
        .param("id", courierId)
        .query(
            (rs, i) ->
                new LocationPing(
                    rs.getObject("courier_id", UUID.class),
                    new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")),
                    rs.getTimestamp("recorded_at").toInstant()))
        .optional();
  }

  public int countHistory(UUID courierId) {
    return jdbc.sql("SELECT count(*) FROM courier_location_history WHERE courier_id = :id")
        .param("id", courierId)
        .query(Integer.class)
        .single();
  }

  private void bind(PreparedStatement ps, LocationPing ping) throws SQLException {
    ps.setObject(1, ping.courierId());
    ps.setDouble(2, ping.point().lng());
    ps.setDouble(3, ping.point().lat());
    ps.setTimestamp(4, Timestamp.from(ping.recordedAt()));
  }
}
