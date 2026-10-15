package com.billyelcalvo.tracking.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything tunable lives here and comes from environment variables (see application.yml). The
 * app refuses to start if a required secret is missing, which is better than running with a
 * default nobody remembered to change.
 */
@Validated
@ConfigurationProperties("tracking")
public record TrackingProperties(
    @Valid @NotNull Security security,
    @Valid @NotNull Ingestion ingestion,
    @Valid @NotNull Assignment assignment,
    @Valid @NotNull WebSocket websocket) {

  public record Security(
      @NotBlank String apiKey,
      @NotBlank @Size(min = 32, message = "must be at least 32 characters for HS256") String jwtSecret,
      @NotNull Duration courierTokenTtl) {}

  public record Ingestion(
      @NotNull Duration flushInterval,
      @Positive int historyQueueCapacity,
      @Positive int maxBatchSize,
      @NotNull Duration minPingInterval) {}

  public record Assignment(
      @Min(100) int searchRadiusMeters,
      @NotNull Duration locationFreshness,
      @NotNull Duration retryInterval,
      @Positive int retryBatchSize) {}

  public record WebSocket(
      @NotNull Duration sendTimeLimit,
      @Positive int sendBufferSizeLimit,
      @NotNull List<String> allowedOrigins) {}
}
