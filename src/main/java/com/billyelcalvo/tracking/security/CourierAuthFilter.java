package com.billyelcalvo.tracking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates the courier app on /api/me/** and exposes the courier id to controllers. */
public class CourierAuthFilter extends OncePerRequestFilter {

  public static final String COURIER_ID_ATTRIBUTE = "authenticatedCourierId";

  private final CourierTokenService tokenService;

  public CourierAuthFilter(CourierTokenService tokenService) {
    this.tokenService = tokenService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isCourierPath(request.getRequestURI());
  }

  static boolean isCourierPath(String path) {
    return path.equals("/api/me") || path.startsWith("/api/me/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<UUID> courierId = tokenService.verify(BearerTokens.extract(request));
    if (courierId.isEmpty()) {
      ProblemResponses.write(
          response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid courier token");
      return;
    }
    request.setAttribute(COURIER_ID_ATTRIBUTE, courierId.get());
    chain.doFilter(request, response);
  }
}
