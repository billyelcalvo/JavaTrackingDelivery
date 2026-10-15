package com.billyelcalvo.tracking.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.billyelcalvo.tracking.TestFixtures;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFiltersTest {

  private final ApiKeyFilter apiKeyFilter = new ApiKeyFilter(TestFixtures.properties());
  private final CourierTokenService tokens =
      new CourierTokenService(TestFixtures.properties(), Clock.systemUTC());
  private final CourierAuthFilter courierFilter = new CourierAuthFilter(tokens);

  @Test
  void apiKeyFilterLetsTheRightKeyThrough() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/orders");
    request.addHeader(ApiKeyFilter.HEADER, TestFixtures.API_KEY);
    var chain = new MockFilterChain();

    apiKeyFilter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void apiKeyFilterBlocksWrongOrMissingKey() throws Exception {
    for (String key : new String[] {null, "wrong", TestFixtures.API_KEY + "x"}) {
      var request = new MockHttpServletRequest("POST", "/api/orders");
      if (key != null) {
        request.addHeader(ApiKeyFilter.HEADER, key);
      }
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();

      apiKeyFilter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentType()).isEqualTo("application/problem+json");
      assertThat(chain.getRequest()).isNull();
    }
  }

  @Test
  void apiKeyFilterIgnoresCourierAppAndNonApiPaths() throws Exception {
    for (String path :
        new String[] {"/api/me", "/api/me/status", "/actuator/health", "/swagger-ui.html"}) {
      var chain = new MockFilterChain();
      apiKeyFilter.doFilter(
          new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).as(path).isNotNull();
    }
  }

  @Test
  void courierFilterExposesTheCourierIdFromTheToken() throws Exception {
    UUID courier = UUID.randomUUID();
    var request = new MockHttpServletRequest("PUT", "/api/me/status");
    request.addHeader("Authorization", "Bearer " + tokens.issue(courier).token());
    var chain = new MockFilterChain();

    courierFilter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(request.getAttribute(CourierAuthFilter.COURIER_ID_ATTRIBUTE)).isEqualTo(courier);
  }

  @Test
  void courierFilterRejectsMissingOrBadToken() throws Exception {
    for (String header : new String[] {null, "Bearer nope", "Basic abc"}) {
      var request = new MockHttpServletRequest("PUT", "/api/me/status");
      if (header != null) {
        request.addHeader("Authorization", header);
      }
      var response = new MockHttpServletResponse();

      courierFilter.doFilter(request, response, new MockFilterChain());

      assertThat(response.getStatus()).isEqualTo(401);
    }
  }
}
