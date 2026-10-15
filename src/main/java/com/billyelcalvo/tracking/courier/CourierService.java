package com.billyelcalvo.tracking.courier;

import com.billyelcalvo.tracking.common.ConflictException;
import com.billyelcalvo.tracking.common.GeoPoint;
import com.billyelcalvo.tracking.common.NotFoundException;
import com.billyelcalvo.tracking.security.CourierTokenService;
import com.billyelcalvo.tracking.security.CourierTokenService.IssuedToken;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierService {

  private static final Logger log = LoggerFactory.getLogger(CourierService.class);

  private final CourierRepository couriers;
  private final CourierTokenService tokens;

  public CourierService(CourierRepository couriers, CourierTokenService tokens) {
    this.couriers = couriers;
    this.tokens = tokens;
  }

  @Transactional
  public RegisteredCourier register(String name) {
    Courier courier = couriers.insert(name.strip());
    log.info("Courier registered courierId={}", courier.id());
    return new RegisteredCourier(courier, tokens.issue(courier.id()));
  }

  public Courier get(UUID id) {
    return couriers.findById(id).orElseThrow(() -> new NotFoundException("Courier not found"));
  }

  /**
   * Couriers can only toggle between OFFLINE and AVAILABLE. BUSY belongs to the assignment flow,
   * otherwise a courier could drop an order just by going offline.
   */
  @Transactional
  public Courier changeAvailability(UUID courierId, CourierStatus target) {
    if (target == CourierStatus.BUSY) {
      throw new IllegalArgumentException("status must be AVAILABLE or OFFLINE");
    }
    Courier current = get(courierId);
    if (current.status() == target) {
      return current;
    }
    if (current.status() == CourierStatus.BUSY) {
      throw new ConflictException("Courier has an active order, finish it first");
    }
    if (!couriers.updateStatus(courierId, current.status(), target)) {
      // Someone (most likely the assignment job) changed it between our read and write
      throw new ConflictException("Courier status changed concurrently, retry");
    }
    log.info("Courier availability changed courierId={} status={}", courierId, target);
    return get(courierId);
  }

  public List<NearbyCourier> nearby(
      GeoPoint point, int radiusMeters, CourierStatus status, int limit) {
    return couriers.findNearby(point, radiusMeters, status, limit);
  }

  public record RegisteredCourier(Courier courier, IssuedToken token) {}
}
