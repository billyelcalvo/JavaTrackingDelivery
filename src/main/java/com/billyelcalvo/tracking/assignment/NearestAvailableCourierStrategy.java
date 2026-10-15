package com.billyelcalvo.tracking.assignment;

import com.billyelcalvo.tracking.config.TrackingProperties;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nearest AVAILABLE courier with a fresh position, by straight-line distance.
 *
 * <p>How the query works:
 *
 * <ul>
 *   <li>{@code <->} on a geography column is a KNN operator: Postgres walks the GiST index in
 *       distance order instead of computing the distance for every courier and sorting.
 *   <li>{@code ST_DWithin} caps the search radius so a city with no free couriers doesn't scan the
 *       whole planet.
 *   <li>{@code FOR UPDATE OF c SKIP LOCKED} locks the chosen courier row. If two orders race for
 *       the same courier, the second one silently moves on to the next nearest instead of waiting
 *       or double-assigning.
 * </ul>
 */
@Component
public class NearestAvailableCourierStrategy implements AssignmentStrategy {

  private static final String QUERY =
      """
      SELECT c.id
      FROM courier_location l
      JOIN courier c ON c.id = l.courier_id
      CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS geog) p
      WHERE c.status = 'AVAILABLE'
        AND l.updated_at >= now() - make_interval(secs => :freshSeconds)
        AND ST_DWithin(l.location, p.geog, :radius)
      ORDER BY l.location <-> p.geog
      LIMIT 1
      FOR UPDATE OF c SKIP LOCKED
      """;

  private final JdbcClient jdbc;
  private final TrackingProperties.Assignment config;

  public NearestAvailableCourierStrategy(JdbcClient jdbc, TrackingProperties properties) {
    this.jdbc = jdbc;
    this.config = properties.assignment();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> pickCourier(DeliveryOrder order) {
    return jdbc.sql(QUERY)
        .param("lat", order.pickup().lat())
        .param("lng", order.pickup().lng())
        .param("radius", config.searchRadiusMeters())
        .param("freshSeconds", (double) config.locationFreshness().toSeconds())
        .query(UUID.class)
        .optional();
  }
}
