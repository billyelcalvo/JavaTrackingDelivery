package com.billyelcalvo.tracking.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/** Filters run before Spring MVC, so they write their problem+json body by hand. */
final class ProblemResponses {

  private ProblemResponses() {}

  static void write(HttpServletResponse response, int status, String detail) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write("{\"status\":" + status + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
  }
}
