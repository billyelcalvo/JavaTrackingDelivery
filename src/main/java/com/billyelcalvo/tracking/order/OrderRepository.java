package com.billyelcalvo.tracking.order;

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
public class OrderRepository {

  private static final String COLUMNS =
      """
      id, status, courier_id, created_at, assigned_at, picked_up_at, delivered_at, cancelled_at,
      ST_Y(pickup::geometry) AS pickup_lat, ST_X(pickup::geometry) AS pickup_lng,
      ST_Y(dropoff::geometry) AS dropoff_lat, ST_X(dropoff::geometry) AS dropoff_lng
      """;

  private final JdbcClient jdbc;

  public OrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public DeliveryOrder insert(GeoPoint pickup, GeoPoint dropoff) {
    return jdbc.sql(
            "INSERT INTO delivery_order (pickup, dropoff) VALUES ("
                + "ST_SetSRID(ST_MakePoint(:pLng, :pLat), 4326)::geography, "
                + "ST_SetSRID(ST_MakePoint(:dLng, :dLat), 4326)::geography) RETURNING "
                + COLUMNS)
        .param("pLat", pickup.lat())
        .param("pLng", pickup.lng())
        .param("dLat", dropoff.lat())
        .param("dLng", dropoff.lng())
        .query(OrderRepository::map)
        .single();
  }

  public Optional<DeliveryOrder> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM delivery_order WHERE id = :id")
        .param("id", id)
        .query(OrderRepository::map)
        .optional();
  }

  public boolean exists(UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM delivery_order WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** Row lock for state transitions; serializes the courier app and the back-office. */
  public Optional<DeliveryOrder> findByIdForUpdate(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM delivery_order WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OrderRepository::map)
        .optional();
  }

  public Optional<DeliveryOrder> findActiveByCourier(UUID courierId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM delivery_order WHERE courier_id = :courierId"
                + " AND status IN ('ASSIGNED', 'PICKED_UP')")
        .param("courierId", courierId)
        .query(OrderRepository::map)
        .optional();
  }

  /**
   * Oldest pending orders, skipping any another instance is already working on. With SKIP LOCKED
   * several app instances can run the retry job at once without a distributed lock.
   */
  public List<DeliveryOrder> lockPendingBatch(int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM delivery_order WHERE status = 'PENDING'"
                + " ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("limit", limit)
        .query(OrderRepository::map)
        .list();
  }

  public List<ActiveAssignment> findActiveAssignments() {
    return jdbc.sql(
            "SELECT id, courier_id FROM delivery_order WHERE status IN ('ASSIGNED', 'PICKED_UP')")
        .query(
            (rs, i) ->
                new ActiveAssignment(
                    rs.getObject("id", UUID.class), rs.getObject("courier_id", UUID.class)))
        .list();
  }

  public DeliveryOrder assign(UUID orderId, UUID courierId) {
    return jdbc.sql(
            "UPDATE delivery_order SET status = 'ASSIGNED', courier_id = :courierId,"
                + " assigned_at = now() WHERE id = :id AND status = 'PENDING' RETURNING "
                + COLUMNS)
        .param("id", orderId)
        .param("courierId", courierId)
        .query(OrderRepository::map)
        .optional()
        .orElseThrow(() -> new IllegalStateException("Order " + orderId + " is not pending"));
  }

  /** Conditional update: only applies if the order is still in {@code from}. */
  public Optional<DeliveryOrder> transition(UUID id, OrderStatus from, OrderStatus to) {
    return jdbc.sql(
            """
            UPDATE delivery_order SET status = :to,
              picked_up_at = CASE WHEN :to = 'PICKED_UP' THEN now() ELSE picked_up_at END,
              delivered_at = CASE WHEN :to = 'DELIVERED' THEN now() ELSE delivered_at END,
              cancelled_at = CASE WHEN :to = 'CANCELLED' THEN now() ELSE cancelled_at END
            WHERE id = :id AND status = :from
            RETURNING """
                + COLUMNS)
        .param("id", id)
        .param("from", from.name())
        .param("to", to.name())
        .query(OrderRepository::map)
        .optional();
  }

  private static DeliveryOrder map(ResultSet rs, int rowNum) throws SQLException {
    return new DeliveryOrder(
        rs.getObject("id", UUID.class),
        OrderStatus.valueOf(rs.getString("status")),
        new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
        new GeoPoint(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
        rs.getObject("courier_id", UUID.class),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("assigned_at")),
        instant(rs.getTimestamp("picked_up_at")),
        instant(rs.getTimestamp("delivered_at")),
        instant(rs.getTimestamp("cancelled_at")));
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  public record ActiveAssignment(UUID orderId, UUID courierId) {}
}
