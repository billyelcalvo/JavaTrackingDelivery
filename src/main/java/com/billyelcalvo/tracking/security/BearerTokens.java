package com.billyelcalvo.tracking.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

final class BearerTokens {

  private static final String PREFIX = "Bearer ";

  private BearerTokens() {}

  static String extract(HttpServletRequest request) {
    return fromHeader(request.getHeader(HttpHeaders.AUTHORIZATION));
  }

  static String fromHeader(String header) {
    if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      return null;
    }
    return header.substring(PREFIX.length()).trim();
  }
}
