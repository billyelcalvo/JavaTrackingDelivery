package com.billyelcalvo.tracking.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.billyelcalvo.tracking.config.TrackingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issues and checks the JWT a courier uses to open its WebSocket and call /api/me endpoints. The
 * token only carries the courier id; everything else is looked up server side.
 */
@Component
public class CourierTokenService {

  private static final String ISSUER = "tracking-delivery";
  private static final String ROLE_CLAIM = "role";
  private static final String COURIER_ROLE = "courier";

  private final Algorithm algorithm;
  private final JWTVerifier verifier;
  private final Duration ttl;
  private final Clock clock;

  public CourierTokenService(TrackingProperties properties, Clock clock) {
    this.algorithm = Algorithm.HMAC256(properties.security().jwtSecret());
    this.ttl = properties.security().courierTokenTtl();
    this.clock = clock;
    this.verifier =
        JWT.require(algorithm).withIssuer(ISSUER).withClaim(ROLE_CLAIM, COURIER_ROLE).build();
  }

  public IssuedToken issue(UUID courierId) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    String token =
        JWT.create()
            .withIssuer(ISSUER)
            .withSubject(courierId.toString())
            .withClaim(ROLE_CLAIM, COURIER_ROLE)
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
            .sign(algorithm);
    return new IssuedToken(token, expiresAt);
  }

  /** Returns the courier id if the token is valid, empty otherwise. Never throws. */
  public Optional<UUID> verify(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(verifier.verify(token).getSubject()));
    } catch (JWTVerificationException | IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  public record IssuedToken(String token, Instant expiresAt) {}
}
