package com.billyelcalvo.tracking.realtime;

import com.billyelcalvo.tracking.config.TrackingProperties;
import com.billyelcalvo.tracking.location.LocationIngestionService;
import com.billyelcalvo.tracking.order.OrderRepository;
import com.billyelcalvo.tracking.order.OrderService;
import com.billyelcalvo.tracking.security.CourierTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Plain WebSockets with a tiny JSON protocol instead of STOMP. Couriers send one small message
 * every few seconds and customers only listen, so a broker protocol would add framing overhead and
 * complexity without buying much here.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

  private final CourierLocationHandler courierHandler;
  private final OrderTrackingHandler trackingHandler;
  private final CourierTokenService tokens;
  private final OrderRepository orderRepository;
  private final String[] allowedOrigins;

  WebSocketConfig(
      LocationIngestionService ingestion,
      OrderService orderService,
      OrderRepository orderRepository,
      TrackingHub hub,
      CourierTokenService tokens,
      ObjectMapper mapper,
      MeterRegistry meters,
      Clock clock,
      TrackingProperties properties) {
    this.courierHandler =
        new CourierLocationHandler(
            ingestion, new PingValidator(clock), mapper, meters, clock, properties);
    this.trackingHandler = new OrderTrackingHandler(hub, orderService, mapper, properties);
    this.tokens = tokens;
    this.orderRepository = orderRepository;
    this.allowedOrigins = properties.websocket().allowedOrigins().toArray(String[]::new);
    Gauge.builder("tracking.ws.sessions", hub, TrackingHub::subscriberCount)
        .tag("type", "tracking")
        .register(meters);
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(courierHandler, "/ws/couriers")
        .addInterceptors(new CourierHandshakeInterceptor(tokens))
        .setAllowedOriginPatterns(allowedOrigins);
    registry
        .addHandler(trackingHandler, "/ws/tracking")
        .addInterceptors(new TrackingHandshakeInterceptor(orderRepository))
        .setAllowedOriginPatterns(allowedOrigins);
  }

  @Bean
  ServletServerContainerFactoryBean webSocketContainer() {
    var container = new ServletServerContainerFactoryBean();
    // A ping is ~60 bytes; anything bigger than this is not a ping
    container.setMaxTextMessageBufferSize(4096);
    container.setMaxBinaryMessageBufferSize(1024);
    return container;
  }
}
