package com.billyelcalvo.tracking.courier;

import com.billyelcalvo.tracking.common.GeoPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CourierRepository {

  private final JdbcClient jdbc;

  public CourierRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Courier insert(String name) {
    return jdbc.sql(
            """
            INSERT INTO courier (name) VALUES (:name)
            RETURNING id, name, status, created_at
            """)
        .param("name", name)
        .query(CourierRepository::mapCourier)
        .single();
  }

  public Optional<Courier> findById(UUID id) {
    return jdbc.sql("SELECT id, name, status, created_at FROM courier WHERE id = :id")
        .param("id", id)
        .query(CourierRepository::mapCourier)
        .optional();
  }

  /**
   * Compare-and-set on the status. Returns false if the courier wasn't in {@code expected}, which
   * lets callers detect races without taking a lock first.
   */
  public boolean updateStatus(UUID id, CourierStatus expected, CourierStatus next) {
    return jdbc.sql(
                """
                UPDATE courier SET status = :next, updated_at = now()
                WHERE id = :id AND status = :expected
                """)
            .param("id", id)
            .param("expected", expected.name())
            .param("next", next.name())
            .update()
        == 1;
  }

  public List<NearbyCourier> findNearby(
      GeoPoint point, int radiusMeters, CourierStatus status, int limit) {
    return jdbc.sql(
            """
            SELECT c.id, c.name, c.status, l.recorded_at,
                   ST_Y(l.location::geometry) AS lat,
                   ST_X(l.location::geometry) AS lng,
                   ST_Distance(l.location, p.geog) AS distance_m
            FROM courier_location l
            JOIN courier c ON c.id = l.courier_id
            CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS geog) p
            WHERE ST_DWithin(l.location, p.geog, :radius)
              AND (CAST(:status AS VARCHAR) IS NULL OR c.status = :status)
            ORDER BY l.location <-> p.geog
            LIMIT :limit
            """)
        .param("lat", point.lat())
        .param("lng", point.lng())
        .param("radius", radiusMeters)
        .param("status", status == null ? null : status.name())
        .param("limit", limit)
        .query(
            (rs, i) ->
                new NearbyCourier(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    CourierStatus.valueOf(rs.getString("status")),
                    new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")),
                    rs.getDouble("distance_m"),
                    toInstant(rs.getTimestamp("recorded_at"))))
        .list();
  }

  private static Courier mapCourier(ResultSet rs, int rowNum) throws SQLException {
    return new Courier(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        CourierStatus.valueOf(rs.getString("status")),
        toInstant(rs.getTimestamp("created_at")));
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
