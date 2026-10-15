package com.billyelcalvo.tracking.security;

import com.billyelcalvo.tracking.config.TrackingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the back-office API (creating couriers and orders). It's meant for trusted services, not
 * end users, so a shared key is enough here.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-API-Key";

  private final byte[] expectedKey;

  public ApiKeyFilter(TrackingProperties properties) {
    this.expectedKey = properties.security().apiKey().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || CourierAuthFilter.isCourierPath(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    // Constant-time comparison so the key can't be guessed byte by byte from response times
    if (provided == null
        || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
      ProblemResponses.write(
          response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
      return;
    }
    chain.doFilter(request, response);
  }
}
